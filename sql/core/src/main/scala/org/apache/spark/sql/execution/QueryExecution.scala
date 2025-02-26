/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.io.{BufferedWriter, OutputStreamWriter}

import org.apache.hadoop.fs.Path

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.spark.sql.catalyst.{InternalRow, QueryPlanningTracker}
import org.apache.spark.sql.catalyst.analysis.UnsupportedOperationChecker
import org.apache.spark.sql.catalyst.expressions.codegen.ByteCodeStats
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, ReturnAnswer}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.StringUtils.PlanStringConcat
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.dynamicpruning.PlanDynamicPruningFilters
import org.apache.spark.sql.execution.adaptive.InsertAdaptiveSparkPlan
import org.apache.spark.sql.execution.exchange.{EnsureRequirements, ReuseExchange}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

/**
 * The primary workflow for executing relational queries using Spark.  Designed to allow easy
 * access to the intermediate phases of query execution for developers.
 *
 * While this is not a public class, we should avoid changing the function names for the sake of
 * changing them, because a lot of developers use the feature for debugging.
 */
class QueryExecution(
    val sparkSession: SparkSession,
    val logical: LogicalPlan,
    val tracker: QueryPlanningTracker = new QueryPlanningTracker) {

  // TODO: Move the planner an optimizer into here from SessionState.
  protected def planner = sparkSession.sessionState.planner

  def assertAnalyzed(): Unit = analyzed

  def assertSupported(): Unit = {
    if (sparkSession.sessionState.conf.isUnsupportedOperationCheckEnabled) {
      UnsupportedOperationChecker.checkForBatch(analyzed)
    }
  }

  lazy val analyzed: LogicalPlan = tracker.measurePhase(QueryPlanningTracker.ANALYSIS) {
    SparkSession.setActiveSession(sparkSession)
    // We can't clone `logical` here, which will reset the `_analyzed` flag.
    sparkSession.sessionState.analyzer.executeAndCheck(logical, tracker)
  }

  lazy val withCachedData: LogicalPlan = {
    assertAnalyzed()
    assertSupported()
    // clone the plan to avoid sharing the plan instance between different stages like analyzing,
    // optimizing and planning.
    sparkSession.sharedState.cacheManager.useCachedData(analyzed.clone())
  }

  lazy val optimizedPlan: LogicalPlan = tracker.measurePhase(QueryPlanningTracker.OPTIMIZATION) {
    // clone the plan to avoid sharing the plan instance between different stages like analyzing,
    // optimizing and planning.
    sparkSession.sessionState.optimizer.executeAndTrack(withCachedData.clone(), tracker)
  }

  lazy val sparkPlan: SparkPlan = tracker.measurePhase(QueryPlanningTracker.PLANNING) {
    // Clone the logical plan here, in case the planner rules change the states of the logical plan.
    QueryExecution.createSparkPlan(sparkSession, planner, optimizedPlan.clone())
  }

  // executedPlan should not be used to initialize any SparkPlan. It should be
  // only used for execution.
  lazy val executedPlan: SparkPlan = tracker.measurePhase(QueryPlanningTracker.PLANNING) {
    // clone the plan to avoid sharing the plan instance between different stages like analyzing,
    // optimizing and planning.
    QueryExecution.prepareForExecution(preparations, sparkPlan.clone())
  }

  /**
   * Internal version of the RDD. Avoids copies and has no schema.
   * Note for callers: Spark may apply various optimization including reusing object: this means
   * the row is valid only for the iteration it is retrieved. You should avoid storing row and
   * accessing after iteration. (Calling `collect()` is one of known bad usage.)
   * If you want to store these rows into collection, please apply some converter or copy row
   * which produces new object per iteration.
   * Given QueryExecution is not a public class, end users are discouraged to use this: please
   * use `Dataset.rdd` instead where conversion will be applied.
   */
  lazy val toRdd: RDD[InternalRow] = new SQLExecutionRDD(
    executedPlan.execute(), sparkSession.sessionState.conf)

  /** Get the metrics observed during the execution of the query plan. */
  def observedMetrics: Map[String, Row] = CollectMetricsExec.collect(executedPlan)

  protected def preparations: Seq[Rule[SparkPlan]] = {
    QueryExecution.preparations(sparkSession)
  }

  def simpleString: String = simpleString(false)

  def simpleString(formatted: Boolean): String = withRedaction {
    val concat = new PlanStringConcat()
    concat.append("== Physical Plan ==\n")
    if (formatted) {
      try {
        ExplainUtils.processPlan(executedPlan, concat.append)
      } catch {
        case e: AnalysisException => concat.append(e.toString)
        case e: IllegalArgumentException => concat.append(e.toString)
      }
    } else {
      QueryPlan.append(executedPlan, concat.append, verbose = false, addSuffix = false)
    }
    concat.append("\n")
    concat.toString
  }

  private def writePlans(append: String => Unit, maxFields: Int): Unit = {
    val (verbose, addSuffix) = (true, false)
    append("== Parsed Logical Plan ==\n")
    QueryPlan.append(logical, append, verbose, addSuffix, maxFields)
    append("\n== Analyzed Logical Plan ==\n")
    val analyzedOutput = try {
      truncatedString(
        analyzed.output.map(o => s"${o.name}: ${o.dataType.simpleString}"), ", ", maxFields)
    } catch {
      case e: AnalysisException => e.toString
    }
    append(analyzedOutput)
    append("\n")
    QueryPlan.append(analyzed, append, verbose, addSuffix, maxFields)
    append("\n== Optimized Logical Plan ==\n")
    QueryPlan.append(optimizedPlan, append, verbose, addSuffix, maxFields)
    append("\n== Physical Plan ==\n")
    QueryPlan.append(executedPlan, append, verbose, addSuffix, maxFields)
  }

  override def toString: String = withRedaction {
    val concat = new PlanStringConcat()
    writePlans(concat.append, SQLConf.get.maxToStringFields)
    concat.toString
  }

  def stringWithStats: String = withRedaction {
    val concat = new PlanStringConcat()
    val maxFields = SQLConf.get.maxToStringFields

    // trigger to compute stats for logical plans
    try {
      optimizedPlan.stats
    } catch {
      case e: AnalysisException => concat.append(e.toString + "\n")
    }
    // only show optimized logical plan and physical plan
    concat.append("== Optimized Logical Plan ==\n")
    QueryPlan.append(optimizedPlan, concat.append, verbose = true, addSuffix = true, maxFields)
    concat.append("\n== Physical Plan ==\n")
    QueryPlan.append(executedPlan, concat.append, verbose = true, addSuffix = false, maxFields)
    concat.append("\n")
    concat.toString
  }

  /**
   * Redact the sensitive information in the given string.
   */
  private def withRedaction(message: String): String = {
    Utils.redact(sparkSession.sessionState.conf.stringRedactionPattern, message)
  }

  /** A special namespace for commands that can be used to debug query execution. */
  // scalastyle:off
  object debug {
  // scalastyle:on

    /**
     * Prints to stdout all the generated code found in this plan (i.e. the output of each
     * WholeStageCodegen subtree).
     */
    def codegen(): Unit = {
      // scalastyle:off println
      println(org.apache.spark.sql.execution.debug.codegenString(executedPlan))
      // scalastyle:on println
    }

    /**
     * Get WholeStageCodegenExec subtrees and the codegen in a query plan
     *
     * @return Sequence of WholeStageCodegen subtrees and corresponding codegen
     */
    def codegenToSeq(): Seq[(String, String, ByteCodeStats)] = {
      org.apache.spark.sql.execution.debug.codegenStringSeq(executedPlan)
    }

    /**
     * Dumps debug information about query execution into the specified file.
     *
     * @param maxFields maximum number of fields converted to string representation.
     */
    def toFile(path: String, maxFields: Int = Int.MaxValue): Unit = {
      val filePath = new Path(path)
      val fs = filePath.getFileSystem(sparkSession.sessionState.newHadoopConf())
      val writer = new BufferedWriter(new OutputStreamWriter(fs.create(filePath)))
      val append = (s: String) => {
        writer.write(s)
      }
      try {
        writePlans(append, maxFields)
        writer.write("\n== Whole Stage Codegen ==\n")
        org.apache.spark.sql.execution.debug.writeCodegen(writer.write, executedPlan)
      } finally {
        writer.close()
      }
    }
  }
}

object QueryExecution {
  /**
   * Construct a sequence of rules that are used to prepare a planned [[SparkPlan]] for execution.
   * These rules will make sure subqueries are planned, make use the data partitioning and ordering
   * are correct, insert whole stage code gen, and try to reduce the work done by reusing exchanges
   * and subqueries.
   */
  private[execution] def preparations(sparkSession: SparkSession): Seq[Rule[SparkPlan]] =
    Seq(
      // `AdaptiveSparkPlanExec` is a leaf node. If inserted, all the following rules will be no-op
      // as the original plan is hidden behind `AdaptiveSparkPlanExec`.
      InsertAdaptiveSparkPlan(sparkSession),
      PlanDynamicPruningFilters(sparkSession),
      PlanSubqueries(sparkSession),
      EnsureRequirements(sparkSession.sessionState.conf),
      ApplyColumnarRulesAndInsertTransitions(sparkSession.sessionState.conf,
        sparkSession.sessionState.columnarRules),
      CollapseCodegenStages(sparkSession.sessionState.conf),
      ReuseExchange(sparkSession.sessionState.conf),
      ReuseSubquery(sparkSession.sessionState.conf)
    )

  /**
   * Prepares a planned [[SparkPlan]] for execution by inserting shuffle operations and internal
   * row format conversions as needed.
   */
  private[execution] def prepareForExecution(
      preparations: Seq[Rule[SparkPlan]],
      plan: SparkPlan): SparkPlan = {
    preparations.foldLeft(plan) { case (sp, rule) => rule.apply(sp) }
  }

  /**
   * Transform a [[LogicalPlan]] into a [[SparkPlan]].
   *
   * Note that the returned physical plan still needs to be prepared for execution.
   */
  def createSparkPlan(
      sparkSession: SparkSession,
      planner: SparkPlanner,
      plan: LogicalPlan): SparkPlan = {
    SparkSession.setActiveSession(sparkSession)
    // TODO: We use next(), i.e. take the first plan returned by the planner, here for now,
    //       but we will implement to choose the best plan.
    planner.plan(ReturnAnswer(plan)).next()
  }

  /**
   * Prepare the [[SparkPlan]] for execution.
   */
  def prepareExecutedPlan(spark: SparkSession, plan: SparkPlan): SparkPlan = {
    prepareForExecution(preparations(spark), plan)
  }

  /**
   * Transform the subquery's [[LogicalPlan]] into a [[SparkPlan]] and prepare the resulting
   * [[SparkPlan]] for execution.
   */
  def prepareExecutedPlan(spark: SparkSession, plan: LogicalPlan): SparkPlan = {
    val sparkPlan = createSparkPlan(spark, spark.sessionState.planner, plan.clone())
    prepareExecutedPlan(spark, sparkPlan)
  }
}
