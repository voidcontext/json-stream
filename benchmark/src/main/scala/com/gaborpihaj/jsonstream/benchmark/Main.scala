package com.gaborpihaj.jsonstream.benchmark

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.LocalDate

import scala.io.Source
import scala.math.Ordering.Implicits._

import cats.effect.{ExitCode, IO}
import cats.instances.either._
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.traverse._
import com.gaborpihaj.jsonstream.benchmark.CliCommand._
import com.gaborpihaj.jsonstream.benchmark.Data.{DataSet, Item, PricePoint}
import com.gaborpihaj.jsonstream.v1.StreamingDecoder.{StreamingDecoderError, decode => streamDecode}
import com.gaborpihaj.jsonstream.v2
import com.gaborpihaj.jsonstream.v2.StreamingDecoder2
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import io.circe.parser.decode
import io.circe.syntax._
//import com.gaborpihaj.jsonstream.StreamingDecoder2

object Main
    extends CommandIOApp(
      name = "benchmark",
      header = "Benchmarking tools for com.gaborpihaj.jsonstream"
    ) {

  val sampleFile = Paths.get("benchmark/generated.json")
  val mb = 1024 * 1024
  val runtime = Runtime.getRuntime

  def findLatestPrice(prices: List[PricePoint]): BigDecimal =
    prices
      .foldLeft(LocalDate.of(1000, 1, 1) -> BigDecimal.valueOf(0)) { (acc, p) =>
        if (acc._1 <= p.date) p.date -> p.price
        else acc
      }
      ._2

  def totalPrice(dataset: DataSet) =
    dataset.values.foldLeft(BigDecimal.valueOf(0))((sum, item) => sum + findLatestPrice(item.prices))

  def memoryUsage(): IO[Long] =
    IO.delay((runtime.totalMemory() - runtime.freeMemory()) / mb)

  def printMemoryUsage(m: Long): IO[Unit] =
    IO.delay(println(s"Current memory usage: ${m}"))

  def repeat[A](fa: IO[A], times: Int): IO[List[A]] =
    List.fill(times)(fa).traverse(identity)

  def printMemoryStats(values: List[Long]): IO[Unit] =
    IO.delay(println("MemoryStats")) >>
      IO.delay(println(s"Average: ${values.sum / values.length}")) >>
      IO.delay(println(s"Max: ${values.max}"))

  def main: Opts[IO[ExitCode]] =
    (generateSampleJson orElse [CliCommand] benchmarkCirce orElse [CliCommand] benchmarkJsonStream orElse [CliCommand] benchmarkJsonStream2).map {
      case GenerateSampleJson     =>
        Data
          .generate[IO]
          .map(_.asJson.toString)
          .flatMap(json => IO.delay(Files.write(sampleFile, json.getBytes(StandardCharsets.UTF_8))))
          .as(ExitCode.Success)
      case BenchmarkCirce(rounds) =>
        (IO.delay(println("parse using circe")) >>
          repeat(
            for {
              result       <- IO.delay(decode[DataSet](Source.fromFile(sampleFile.toUri()).mkString))
              errorOrTotal <- IO.delay(result.map(totalPrice))
              _            <- IO.delay(println(errorOrTotal))
              mem          <- memoryUsage()
              _            <- printMemoryUsage(mem)
            } yield mem,
            rounds
          ) >>= printMemoryStats).as(ExitCode.Success)

      case BenchmarkJsonStream(rounds) =>
        (IO.delay(println("parse using json-stream")) >>
          repeat(
            for {
              errorOrTotal <-
                streamDecode[IO, Item](sampleFile.toFile())
                  .use(
                    _.map(_.map(kv => findLatestPrice(kv._2.prices))).compile
                      .fold[Either[StreamingDecoderError, BigDecimal]](Right(BigDecimal.valueOf(0)))((sum, t) =>
                        (sum, t).mapN(_ + _)
                      )
                  )
              _            <- IO.delay(println(errorOrTotal))
              mem          <- memoryUsage()
              _            <- printMemoryUsage(mem)
            } yield mem,
            rounds
          ) >>= printMemoryStats).as(ExitCode.Success)

      case BenchmarkJsonStream2(rounds) =>
        (IO.delay(println("parse using json-stream v2")) >>
          IO.delay {
            implicit val ppDecoder: v2.Decoder[PricePoint] = StreamingDecoder2.deriveDecoder
            val decoder: v2.Decoder[Item] = StreamingDecoder2.deriveDecoder
            val streaming: StreamingDecoder2.StreamingDecoder[IO] = StreamingDecoder2.decoder[IO]

            streaming -> decoder
          }.flatMap { case (streaming, decoder) =>
            implicit val d: v2.Decoder[Item] = decoder
            repeat(
              for {
                inputStream  <- IO.delay(new FileInputStream(sampleFile.toFile()))
                errorOrTotal <-
                  streaming
                    .decodeKeyValues[Item](inputStream)
                    .use(
                      _.map(_.map(kv => findLatestPrice(kv._2.prices))).compile
                        .fold[Either[v2.StreamingDecoderError, BigDecimal]](
                          Right(BigDecimal.valueOf(0))
                        )((sum, t) => (sum, t).mapN(_ + _))
                    )
                _            <- IO.delay(println(errorOrTotal))
                mem          <- memoryUsage()
                _            <- printMemoryUsage(mem)
              } yield mem,
              rounds
            ) >>= printMemoryStats
          }).as(ExitCode.Success)
    }

}
