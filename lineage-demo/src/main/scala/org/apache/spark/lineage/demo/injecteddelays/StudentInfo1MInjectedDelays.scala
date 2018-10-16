package org.apache.spark.lineage.demo.injecteddelays

/**
 * Modified by Katherine
 * Originally created by Michael on 4/14/16.
 * Modified by jteoh (after Katherine) on 8/30/16
 */

import org.apache.spark.SparkConf
import org.apache.spark.lineage.LineageContext
import org.apache.spark.lineage.LineageContext._
import org.apache.spark.lineage.demo.LineageBaseApp
import org.apache.spark.lineage.perfdebug.lineageV2.LineageWrapper._
import org.apache.spark.lineage.rdd.Lineage


object StudentInfo1MInjectedDelays extends LineageBaseApp(
                                          threadNum = Some(6), // jteoh retained from original
                                          lineageEnabled = true,
                                          sparkLogsEnabled = false,
                                          sparkEventLogsEnabled = true,
                                          igniteLineageCloseDelay = 10000
                                         ){
  
  var logFile: String = _
  //  private val exhaustive = 0
  override def initConf(args: Array[String], defaultConf: SparkConf): SparkConf = {
    // jteoh: only conf-specific configuration is this one, which might not be required for usual
    // execution.
    //defaultConf.set("spark.executor.memory", "2g")
    logFile = args.headOption.getOrElse("/Users/jteoh/Code/Performance-Debug-Benchmarks/StudentInfo/studentData_1M_bias0_0.30.txt")
    //defaultConf.setAppName(s"${appName}-${logFile}")
    defaultConf
  }
  
  def run(lc: LineageContext, args: Array[String]): Unit = {
    //set up logging
    //    val lm: LogManager = LogManager.getLogManager
    //    val logger: Logger = Logger.getLogger(getClass.getName)
    //    val fh: FileHandler = new FileHandler("myLog")
    //    fh.setFormatter(new SimpleFormatter)
    //    lm.addLogger(logger)
    //    logger.setLevel(Level.INFO)
    //    logger.addHandler(fh)
    
    
    //start recording time for lineage
    /**************************
        Time Logging
     **************************/
    //    val jobStartTimestamp = new java.sql.Timestamp(Calendar.getInstance.getTime.getTime)
    //    val jobStartTime = System.nanoTime()
    //    logger.log(Level.INFO, "JOb starts at " + jobStartTimestamp)
    /**************************
        Time Logging
     **************************/
    //spark program starts here
    println(s"Using logFile $logFile")
    val records = lc.textFile(logFile)
    val recordsWithInjectedDelay = records.map(injectDelays)
    val grade_age_pair = recordsWithInjectedDelay.map(line => {
      val list = line.split(" ")
      (list(4).toInt, list(3).toInt)
    })
    val average_age_by_grade = grade_age_pair.groupByKey()
                               .map(pair => {
                                 val itr = pair._2.toIterator
                                 var moving_average = 0.0
                                 var num = 1
                                 while (itr.hasNext) {
                                   moving_average = moving_average + (itr.next() - moving_average) / num
                                   num = num + 1
                                 }
                                 (pair._1, moving_average)
                               })
    
    val out = Lineage.measureTimeWithCallback({
      average_age_by_grade.collect()
    }, x => println(s"Collect time: $x ms"))
    /**************************
        Time Logging
     **************************/
    //    println(">>>>>>>>>>>>>  First Job Done  <<<<<<<<<<<<<<<")
    //    val jobEndTimestamp = new java.sql.Timestamp(Calendar.getInstance.getTime.getTime)
    //    val jobEndTime = System.nanoTime()
    //    logger.log(Level.INFO, "JOb ends at " + jobEndTimestamp)
    //    logger.log(Level.INFO, "JOb span at " + (jobEndTime-jobStartTime)/1000 + "milliseconds")
    /**************************
        Time Logging
     **************************/
    //print out the result for debugging purpose
    for (o <- out) {
      println( o._1 + " - " + o._2)
    }
    
    println(average_age_by_grade.toDebugString)
    
    // runPerformanceTrace(records, grade_age_pair, average_age_by_grade)
  
    /**************************
        *Time Logging
     **************************/
    //    val DeltaDebuggingStartTimestamp = new java.sql.Timestamp(Calendar.getInstance.getTime.getTime)
    //    val DeltaDebuggingStartTime = System.nanoTime()
    //    logger.log(Level.INFO, "Record DeltaDebugging + L  (unadjusted) time starts at " + DeltaDebuggingStartTimestamp)
    /**************************
        Time Logging
     **************************/
    
    
    
    //    val delta_debug = new DDNonExhaustive[String]
    //    delta_debug.setMoveToLocalThreshold(0);
    //    val returnedRDD = delta_debug.ddgen(records, new Test, new SequentialSplit[String], lm, fh , DeltaDebuggingStartTime)
    
    /**************************
        Time Logging
     **************************/
    //    val DeltaDebuggingEndTime = System.nanoTime()
    //    val DeltaDebuggingEndTimestamp = new java.sql.Timestamp(Calendar.getInstance.getTime.getTime)
    //    logger.log(Level.INFO, "DeltaDebugging (unadjusted) + L  ends at " + DeltaDebuggingEndTimestamp)
    //    logger.log(Level.INFO, "DeltaDebugging (unadjusted)  + L takes " + (DeltaDebuggingEndTime - DeltaDebuggingStartTime) / 1000 + " milliseconds")
    /**************************
        Time Logging
     **************************/
    println("Job's DONE!")
  }
  
  private def runPerformanceTrace(records: Lineage[String], grade_age_pair: Lineage[(Int, Int)],
                                  average_age_by_grade: Lineage[(Int, Double)]) = {
    println(average_age_by_grade.toDebugString)
    
    // Equivalent, but some sort of boxing issue where Spark is getting a Tuple2 but
    // expecting a Long. This is probably caching issue in Titian?
    // grade_age_pair.countByKey()
    grade_age_pair.mapValues(_ => 1L).reduceByKey(_ + _).collect().toMap.foreach(println)
    
    val slowestRec = average_age_by_grade.lineageWrapper
                     .tracePerformance(printDebugging = true)
                     .take(1)
    printHadoopSources(slowestRec, records)
    slowestRec.traceBackAll().joinInputTextRDD(records)
  }
  
  private def injectDelays(line: String): String = {
    val firstName = line.split(" ")(0)
    firstName match {
      case "iiauhwy" =>
        // line 42, yay HGTTG reference?
        Thread.sleep(5000)
      case "vmoruhwey" =>
        // line 265, just randomly scrolled and chosen
        Thread.sleep(3000)
      case "wdtgwbysbg" =>
        // line 816569, again random
        Thread.sleep(2500)
      case "vjsxuypr" =>
        // line 544696, again random
        Thread.sleep(1500)
      case _ => // noop
    }
    line
  }
}