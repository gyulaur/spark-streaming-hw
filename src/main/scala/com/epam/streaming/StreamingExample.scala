package com.epam.streaming

import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.{DStream, InputDStream}

import scala.reflect.io.Directory


object StreamingExample {


  def main(args: Array[String]): Unit = {
    require(args.length == 4, "Provide parameters in this order: actorsDataFolderPath, ratingEventsDataFolderPath, minimumNumberOfVotes, minimumNumberOfMovies")

    val actorsFolder = args(0);
    val ratingFolder = args(1);
    val minimumNumberOfVotes = args(2).toInt
    val minimumNumberOfMovies = args(3).toInt

    val spark = SparkSession.builder
      .master("local[4]")
      .appName("Imdb - Spark Core")
      .getOrCreate()

    val sparkContext = spark.sparkContext
    val sparkStreamingContext = new StreamingContext(sparkContext, Seconds(1))
    sparkStreamingContext.checkpoint(createTempDir)

    val imdbEventGenerator = new ImdbEventGenerator(actorsFolder, ratingFolder)
    val actorsOfMovies: InputDStream[String] = sparkStreamingContext.queueStream(imdbEventGenerator.buildImdbEventStream(sparkContext, DataType.ActorData))
    val movieRatingEvents: InputDStream[String] = sparkStreamingContext.queueStream(imdbEventGenerator.buildImdbEventStream(sparkContext, DataType.RatingData))

    // printing 15-15 lines from each input file in each microbatch
    actorsOfMovies.print(15)
    movieRatingEvents.print(15)


    // =========================================   TASK 1:   =========================================
    // |   printing out the 10 most busy actors (those actors who played in the most of the films)   |
    // ===============================================================================================
    val numberOfMoviesByActors: DStream[(String, Long)] = task1(actorsOfMovies)

    numberOfMoviesByActors.foreachRDD(rdd => {
      println("\n==== TASK 1: busy actors ====")
      rdd
        .takeOrdered(10)(Ordering[Long].reverse.on(_._2)) // taking the actors with the highest 10 movie number
        .foreach(data => println(s"actor: ${data._1}, number of movies:  ${data._2}"))
    })


    // =========================================   TASK 2:   =========================================
    // |   printing out the 10 best actors (those, who get the highest average rates and also voted  |
    // |   at least 'minimumNumberOfVotes' times)                                                    |
    // |   NOTE! each line in the movie rating datafile means 1000 actual votes                      |
    // ===============================================================================================
    val actorRatingsSinceBeginningOfTime: DStream[(String, Long, Float)] =
    task2(actorsOfMovies, movieRatingEvents)

    actorRatingsSinceBeginningOfTime
      .filter(_._2 >= minimumNumberOfVotes)
      .foreachRDD(rdd => {
        println(s"\n==== TASK 2: best actors (at least $minimumNumberOfVotes votes) ====")
        rdd
          .takeOrdered(10)(Ordering[Float].reverse.on(_._3)) // taking the actors with the highest 10 average ratings
          .foreach(data => println(s"actor: ${data._1}, average rate:  ${data._3}, number of votes: ${data._2}"))
      })



    // =========================================   TASK 3:   =========================================
    // |   printing out the 10 best busy actors (those, who get the highest average rates and also   |
    // |   played in at least 'minimumNumberOfMovies' movies). We don't care about the number of     |
    // |   votes in this task)                                                                       |
    // ===============================================================================================
    val actorRatingsAndMovieNumberSinceBiginningOfTime: DStream[(String, Float, Long)] =
    task3(actorRatingsSinceBeginningOfTime, numberOfMoviesByActors, minimumNumberOfMovies)

    actorRatingsAndMovieNumberSinceBiginningOfTime
      .foreachRDD(rdd => {
        println(s"\n==== TASK 3: best busy actors (at least $minimumNumberOfMovies movies) ====")
        rdd
          .takeOrdered(10)(Ordering[Float].reverse.on(_._2)) // taking the actors with the highest 10 average ratings
          .foreach(data => println(s"actor: ${data._1}, average rate:  ${data._2}, number of movies: ${data._3}"))
      })


    sparkStreamingContext.start() // Start the computation
    sparkStreamingContext.awaitTermination() // Wait for the computation to terminate
    spark.stop()
  }


  /**
    * function implementing task 1
    *
    * @param actorsOfMovies each line of the actor data input file fetched in the given microbatch
    * @return a DStream of:
    *         - actor name,
    *         - number of movies where the actor played since the beginning of time
    */
  def task1(actorsOfMovies: InputDStream[String]): DStream[(String, Long)] = {
    actorsOfMovies.map(line => line.split("\t")).map(data => (data(0), 1L)).updateStateByKey(updateActors)
  }

  def updateActors(newValues: Seq[Long], count: Option[Long]): Option[Long] = {
    Some(count.getOrElse(0L) + newValues.size)
  }

  /**
    * function implementing task 2
    *
    * You need to calculate the average rate by taking all the votes into account (and not by taking the average of the averages)
    *
    * @param actorsOfMovies       each line of the actor data input file fetched in the given microbatch
    * @param movieRatingEvents    each line of the movie rating data input file fetched in the given microbatch
    *                             NOTE! each line in the data file means 1000 votes
    * @return a DStream of:
    *         - actor name,
    *         - number of votes since the beginning of time for the movies where the actor playes,
    *         - average rating of movies where the actor played since the beginning of time
    */
  def task2(actorsOfMovies: InputDStream[String], movieRatingEvents: InputDStream[String]): DStream[(String, Long, Float)] = {
    val actorsWithMovies = actorsOfMovies
      .map(actorWithMovie => actorWithMovie.split("\t"))
      .map(data => (data(1), data(0)))

    val moviesWithRatings = movieRatingEvents
      .map(movieWithRating => movieWithRating.split("\t"))
      .map(data => (data(1), data(0).toDouble))

    moviesWithRatings
      .join(actorsWithMovies)
      .map(line => (line._2._2, (1L, line._2._1.toFloat)))
      .updateStateByKey(updateActorsWithRatings)
      .map(actorsWithRatingsTuple => (actorsWithRatingsTuple._1, actorsWithRatingsTuple._2._1 * 1000, actorsWithRatingsTuple._2._2 / actorsWithRatingsTuple._2._1))
      .transform(rdd => rdd.sortBy(_._3, false))
  }

  def updateActorsWithRatings(newRatings: Seq[(Long, Float)], currentRatings: Option[(Long, Float)]): Option[(Long, Float)] = {
    val sumOfRatings = currentRatings.getOrElse((0, 0f))._2 + newRatings.map(ratingAndCount => ratingAndCount._2).sum
    val newCount = currentRatings.getOrElse((0L, 0f))._1 + newRatings.map(x => x._1).sum
    Some((newCount, sumOfRatings))
  }

  /**
    * function implementing task 3
    *
    * @param actorRatingsSinceBeginningOfTime result of task2 function
    * @param numberOfMoviesByActors           result of task1 function
    * @param minimumNumberOfMovies            input argument, specifying the minimum (inclusive) number of movies
    * @return a DStream of:
    *         - actor name,
    *         - average rating of movies where the actor played since the beginning of time
    *         - number of movies since the beginning of time where the actor played
    */
  def task3(actorRatingsSinceBeginningOfTime: DStream[(String, Long, Float)],
            numberOfMoviesByActors: DStream[(String, Long)],
            minimumNumberOfMovies: Int): DStream[(String, Float, Long)] = {
    val actorRatings = actorRatingsSinceBeginningOfTime
      .map(actorRating => (actorRating._1, (actorRating._2, actorRating._3)))
    numberOfMoviesByActors
      .filter(actorWithMovies => actorWithMovies._2 >= minimumNumberOfMovies)
      .join(actorRatings)
      .map(result => (result._1, result._2._2._2, result._2._1))
  }


  def createTempDir: String = {
    // replacing backslashes to enable it to work on windows
    Directory.makeTemp(suffix = "SparkStreamingHwCheckpoint").toFile.jfile.getAbsolutePath.replace("\\", "/")
  }
}
