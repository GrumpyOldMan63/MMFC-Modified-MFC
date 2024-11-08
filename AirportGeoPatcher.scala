package com.patson.init

import com.patson.data.{AirportSource, CitySource, CountrySource}
import com.patson.init.GeoDataGenerator.{CsvAirport}
import com.patson.model._

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * Regenerate ALL airport data (pops, runway, power etc) without wiping the existing airport DB
  *
  * It will attempt to update the airport if it's already existed and insert airport otherwise
  *
  * it will NOT purge airports that no longer in the CSV file tho
  *
  */
object AirportGeoPatcher extends App {

  mainFlow

  def mainFlow() {
    val existingAirports = AirportSource.loadAllAirports(false)
    val iataToGeneratedId : Map[String, Int] = existingAirports.map(airport => (airport.iata, airport.id)).toMap //just load to get IATA to our generated ID

    val csvAirports : List[CsvAirport] = Await.result(GeoDataGenerator.getAirport(), Duration.Inf).map { csvAirport =>
      val rawAirport = csvAirport.airport
      val csvAirportId = csvAirport.csvAirportId
      val scheduleService = csvAirport.scheduledService

      iataToGeneratedId.get(rawAirport.iata) match {
        case Some(savedId) => CsvAirport(rawAirport.copy(id = savedId), csvAirportId, scheduleService)
        case None => csvAirport
      }
    }
    val runways : Map[Int, List[Runway]] = Await.result(GeoDataGenerator.getRunway(), Duration.Inf)

    val cities = AdditionalLoader.loadAdditionalCities()
    //make sure cities are saved first as we need the id for airport info
    try {
//      AirportSource.deleteAllAirports()
      CitySource.deleteAllCitites()
      CitySource.saveCities(cities)
    } catch {
      case e : Throwable => e.printStackTrace()
    }

    val computedAirports = GeoDataGenerator.generateAirportData(csvAirports, runways, cities)

    val newAirports = computedAirports.filter(_.id == 0)
    val updatingAirports = computedAirports.filter(_.id > 0)

    GeoDataGenerator.setAirportRunwayDetails(csvAirports, runways)
    println(s"Creating ${newAirports.length} Airports")
    AirportSource.saveAirports(newAirports)

    println(s"Updating ${updatingAirports.length} Airports")
    AirportSource.updateAirports(updatingAirports)

//    val deletingAirportIds = existingAirports.map(_.id).diff(computedAirports.map(_.id))
//    println(s"Deleting ${deletingAirportIds.length} Airports")
//    AirportSource.deleteAirports(deletingAirportIds)


    AirportFeaturePatcher.patchFeatures()
    GenericTransitGenerator.generateGenericTransit()

    GeoDataGenerator.buildCountryData(updatingAirports, update = true)

    Await.result(actorSystem.terminate(), Duration.Inf)
  }



}