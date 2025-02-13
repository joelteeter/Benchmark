package models

/**
 * Created by rimukas on 10/20/15.
 */

import play.api.libs.json._
import play.api.libs.json.Reads._
import squants.energy.{Gigajoules, KBtus}
import squants.space.{Area, SquareFeet, SquareMeters}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import play.api.libs.functional.syntax._
import squants.energy._
import EnergyConversions.EnergyNumeric
import squants.energy

import scala.util._
import scala.util.control.NonFatal

case class EUICalculator(parameters: JsValue) {

  implicit def boolOptToInt(b: Option[Boolean]): Int = if (b.getOrElse(false)) 1 else 0

  def country: String = {
    parameters.asOpt[ConversionInfo] match {
      case Some(a) => {
        a.country match {
          case Some(country:String) => country
          case _ => throw new Exception("Could not retrieve Country")
        }
      }
      case _ => throw new Exception("Could not retrieve Country")
    }
  }
  def reportingUnits: String = {
    parameters.asOpt[ConversionInfo] match {
      case Some(a) => {
        a.reportingUnits match {
          case Some(reportingUnits:String) => reportingUnits
          case None => "us"
        }
      }
      case _ => throw new Exception("Could not retrieve Reporting Units")
    }
  }


  def sourceEnergynoPoolnoParking:Future[Energy] = {
    for {
      poolEnergy <- getPoolEnergy
      //parkingEnergy <- getParkingEnergy
      sourceTotalEnergy <- getTotalSourceEnergy
      sourceEnergy <- Future(sourceTotalEnergy - poolEnergy)// - parkingEnergy)
    } yield sourceEnergy
  }

  def getSiteEnergyList: Future[List[EnergyTuple]] = {
    for {
      entries <- getEnergyList
      siteEnergyList <- computeSiteEnergy(entries)
    } yield siteEnergyList
  }


  def getSiteRenewableEnergyList: Future[List[EnergyTuple]] = {
    for {
      entries <- getRenewableEnergyList
      siteRenewableEnergyList <- computeSiteEnergy(entries)
    } yield siteRenewableEnergyList
  }

  def getRenewableEnergyTotalbyType(entryList:List[EnergyTuple],entryType:String): Future[Energy] = Future {
    entryType match {
      case "onSite" => entryList.filterNot(_.energyName == "Electric (renewable)").map(_.energyValue).sum
      case "purchased" => entryList.filter(_.energyName == "Electric (renewable)").map(_.energyValue).sum
      case _ => entryList.map(_.energyValue).sum
    }
  }

  //this function will output in double in expected units, while the function above will output in watt-hours
  //in order to be used in downstreams arithmetic operations
  def getRenewableEnergyTotalbyTypeOutput(entryList:List[EnergyTuple],entryType:String): Future[Double] = Future {
    entryType match {
      case "onSite" => entryList.filterNot(_.energyName == "Electric (renewable)").map(_.energyValue.value).sum
      case "purchased" => entryList.filter(_.energyName == "Electric (renewable)").map(_.energyValue.value).sum
      case _ => 0.0
    }
  }

  def getSiteEnergyTotalbyType(entryList:List[EnergyTuple]): Future[Double] = Future {
    entryList.map(_.energyValue.value).sum
  }

  def getTotalSiteEnergy: Future[Energy] = {
    for {
      siteEnergyList <- getSiteEnergyList
      siteEnergySum <- getSiteEnergySum(siteEnergyList)
      siteRenewableSum <- getTotalSiteRenewableEnergy
    } yield siteEnergySum - siteRenewableSum
  }


  def getTotalSiteRenewableEnergy: Future[Energy] = {
    val local = for {
      entries <- getRenewableEnergyList
      siteRenewableEnergyList <- computeSiteEnergy(entries)
      siteRenewableEnergySum <- getSiteEnergySum(siteRenewableEnergyList)
    } yield {siteRenewableEnergySum}
    local.recoverWith{
      case NonFatal(th) =>  Future{countryZero}
    }
  }

  def getSiteEnergySum(energies: List[EnergyTuple]): Future[Energy] = {
    val f = country match {
      case "USA" => energies.map {
        case a: EnergyTuple =>
          a.energyName match {
            case "Sold" => a.energyValue*(-1) in KBtus
            case _ => a.energyValue in KBtus
          }
    }.sum in KBtus
      case _ => energies.map {
        case a: EnergyTuple =>
          a.energyName match {
            case "Sold" => a.energyValue*(-1) in Gigajoules
            case _ => a.energyValue in Gigajoules
          }
      }.sum in Gigajoules
    }
    Future(f)
  }

  def mapEnergy(units:String,value:Double):Option[Energy] = {
    var energyValue =units match {
      case "kBtu" => Some(KBtus(value))
      case "MBtu" => Some(MBtus(value))
      case "kWh" => Some(KilowattHours(value))
      case "MWh" => Some(MegawattHours(value))
      case "GJ" => Some(Gigajoules(value))
      case "NG Mcf" => Some(NGMCfs(value))
      case "NG kcf" => Some(NGKCfs(value))
      case "NG ccf" => Some(NGCCfs(value))
      case "NG cf" => Some(NGCfs(value))
      case "NGm3" => Some(NGm3s(value))
      case "therms" => Some(Therms(value))
      case "No1 igal" => Some(OilNo1UKGs(value))
      case "No1 gal" => Some(OilNo1USGs(value))
      case "No1 L" => Some(OilNo1Ls(value))
      case "No2 igal" => Some(OilNo2UKGs(value))
      case "No2 gal" => Some(OilNo2USGs(value))
      case "No2 L" => Some(OilNo2Ls(value))
      case "No4 igal" => Some(OilNo4UKGs(value))
      case "No4 gal" => Some(OilNo4USGs(value))
      case "No4 L" => Some(OilNo4Ls(value))
      case "No6 igal" => Some(OilNo6UKGs(value))
      case "No6 gal" => Some(OilNo6USGs(value))
      case "No6 L" => Some(OilNo6Ls(value))
      case "Diesel igal" => Some(DieselUKGs(value))
      case "Diesel gal" => Some(DieselUSGs(value))
      case "Diesel L" => Some(DieselLs(value))
      case "Kerosene igal" => Some(KeroseneUKGs(value))
      case "Kerosene gal" => Some(KeroseneUSGs(value))
      case "Kerosene L" => Some(KeroseneLs(value))
      case "Propane igal" => Some(PropaneUKGs(value))
      case "Propane gal" => Some(PropaneUSGs(value))
      case "Propane cf" => Some(PropaneCfs(value))
      case "Propane ccf" => Some(PropaneCCfs(value))
      case "Propane kcf" => Some(PropaneKCfs(value))
      case "Propane L" => Some(PropaneLs(value))
      case "Steam lb" => Some(SteamLbs(value))
      case "Steam klb" => Some(SteamKLbs(value))
      case "Steam Mlb" => Some(SteamMLbs(value))
      case "CHW TonH" => Some(CHWTonHs(value))
      case "CoalA ton" => Some(CoalATons(value))
      case "CoalA tonne" => Some(CoalATonnes(value))
      case "CoalA lb" => Some(CoalALbs(value))
      case "CoalA klb" => Some(CoalAKLbs(value))
      case "CoalA Mlb" => Some(CoalAMLbs(value))
      case "CoalBit ton" => Some(CoalBitTons(value))
      case "CoalBit tonne" => Some(CoalBitTonnes(value))
      case "CoalBit lb" => Some(CoalBitLbs(value))
      case "CoalBit klb" => Some(CoalBitKLbs(value))
      case "CoalBit Mlb" => Some(CoalBitMLbs(value))
      case "Coke ton" => Some(CokeTons(value))
      case "Coke tonne" => Some(CokeTonnes(value))
      case "Coke lb" => Some(CokeLbs(value))
      case "Coke klb" => Some(CokeKLbs(value))
      case "Coke Mlb" => Some(CokeMLbs(value))
      case "Wood ton" => Some(WoodTons(value))
      case "Wood tonne" => Some(WoodTonnes(value))
      case _ => None
    }
    return energyValue
  }

  def computeSiteEnergy[T](entries: T): Future[List[EnergyTuple]] = Future {
    entries match {
      case a: EnergyList => a.energies.map {
        case a: EnergyMetrics => {
          mapEnergy(a.energyUnits,a.energyUse) match {
            case Some(b) => EnergyTuple(a.energyType,a.energyName,b)
            case None => throw new Exception("Could not determine energy unit for entry")
          }
        }
      }
      case a: RenewableEnergyList => a.renewableEnergies.map {
        case a: EnergyMetrics => {
          mapEnergy(a.energyUnits,a.energyUse) match {
            case Some(b) => EnergyTuple(a.energyType,a.energyName,b)
            case None => throw new Exception("Could not determine energy unit for entry")
          }
        }
      }
      case _ => throw new Exception("Could not validate energy entries to calculate EUI")
    }
  }

  def getSourceEnergy: Future[List[EnergyTuple]] = {
    for {
      entries <- getEnergyList
      sourceEnergyList <- computeSourceEnergy(entries)
      sourceEnergyListConverted <- convertOutput(sourceEnergyList)
    } yield {
      sourceEnergyListConverted
    }
  }

  def getTotalSourceEnergy: Future[Energy] = {
    for {
      entries <- getEnergyList
      sourceEnergyList <- computeSourceEnergy(entries)
      sourceEnergyListConverted <- convertOutputSum(sourceEnergyList)
      totalSourceRenewableEnergy <- getTotalSourceRenewableEnergy

    } yield sourceEnergyListConverted - totalSourceRenewableEnergy
  }

  def getTotalSourceRenewableEnergy: Future[Energy] = {
    val local = for {
      entries <- getRenewableEnergyList
      sourceRenewableEnergyList <- computeSourceEnergy(entries)
      sourceRenewableEnergySum <- convertOutputSum(sourceRenewableEnergyList)
    } yield {sourceRenewableEnergySum}
    local.recoverWith{
      case NonFatal(th) =>  Future{countryZero}
    }
  }


  def getTotalSourceEnergyNoPoolNoParking: Future[Energy] = {
    for {
      sourceEnergyListConverted <- getTotalSourceEnergy
      poolEnergy <- getPoolEnergy
      //parkingEnergy <- getParkingEnergy

    } yield sourceEnergyListConverted - poolEnergy// - parkingEnergy
  }

  def computeSourceEnergy[T](entries: T): Future[List[EnergyTuple]] = Future{
    entries match {
      case a: EnergyList => a.energies.map {
        case a: EnergyMetrics => sourceConvert(a.energyType,a.energyName, mapEnergy(a.energyUnits,a.energyUse))
      }
      case a: RenewableEnergyList => a.renewableEnergies.map {
        case a: EnergyMetrics => sourceConvert(a.energyType,a.energyName, mapEnergy(a.energyUnits,a.energyUse))
      }
      case _ => throw new Exception("Could not compute Source Energy from Site Energy")
    }
  }

  def getEnergyList: Future[EnergyList] = {
    for {
      entries <- Future {
        parameters.validate[EnergyList]
      }
      siteEnergyList <- {
        entries match {
          case JsSuccess(a, _) => Future(a)
          case JsError(err) => throw new Exception("Could not read provided energy list")
        }
      }
    } yield siteEnergyList
  }

  def getRenewableEnergyList: Future[RenewableEnergyList] = {
    for {
      entries <- Future {
        parameters.validate[RenewableEnergyList]
      }
      siteRenewableEnergyList <- {
        entries match {
          case JsSuccess(a, _) => Future(a)
          case JsError(err) => throw new Exception("Could not read provided energy list")
        }
      }
    } yield siteRenewableEnergyList
  }

  def convertOutput(energies: List[EnergyTuple]): Future[List[EnergyTuple]] = Future {
    country match {
      case "USA" => energies.map {case a:EnergyTuple => EnergyTuple(a.energyType,a.energyName,a.energyValue in KBtus)}
      case _ => energies.map {case a:EnergyTuple => EnergyTuple(a.energyType,a.energyName, a.energyValue in Gigajoules)}
    }
  }

  def convertOutputSum(energies: List[EnergyTuple]): Future[Energy] = Future {
    country match {
      case "USA" => energies.map {case a:EnergyTuple => a.energyValue in KBtus}.sum in KBtus
      case _ => energies.map {case a:EnergyTuple => a.energyValue in Gigajoules}.sum in Gigajoules
    }
  }

  def sourceConvert(energyType: String, energyName:String, siteEnergy: Option[Energy]): EnergyTuple = {
    val sourceEnergyValue = siteEnergy match {
        case Some(a) => {
        (energyType, country) match {
        case ("grid", "USA") => a * siteToSourceConversions.gridUS
        case ("grid", "Canada") => a * siteToSourceConversions.gridCanada
        case ("naturalGas", "USA") => a * siteToSourceConversions.ngUS
        case ("naturalGas", "Canada") => a * siteToSourceConversions.ngCanada
        case ("onSiteElectricity", _) => a * siteToSourceConversions.onSiteElectricity
        case ("fuelOil1", _) => a * siteToSourceConversions.fuelOil
        case ("fuelOil2", _) => a * siteToSourceConversions.fuelOil
        case ("fuelOil4", _) => a * siteToSourceConversions.fuelOil
        case ("fuelOil6", _) => a * siteToSourceConversions.fuelOil
        case ("kerosene", _) => a * siteToSourceConversions.fuelOil
        case ("diesel", _) => a * siteToSourceConversions.fuelOil
        case ("propane", "USA") => a * siteToSourceConversions.propaneUS
        case ("propane", "Canada") => a * siteToSourceConversions.propaneCanada
        case ("steam", _) => a * siteToSourceConversions.steam
        case ("hotWater", _) => a * siteToSourceConversions.hotWater
        case ("chilledWater", "USA") => a * siteToSourceConversions.chilledWaterUS
        case ("chilledWater", "Canada") => a * siteToSourceConversions.chilledWaterCanada
        case ("wood", _) => a * siteToSourceConversions.wood
        case ("coke", _) => a * siteToSourceConversions.coke
        case ("coalA", _) => a * siteToSourceConversions.coal
        case ("coalB", _) => a * siteToSourceConversions.coal
        case ("other", _) => a * siteToSourceConversions.other
        case ("onSiteSolar", _) => a * siteToSourceConversions.gridUS
        case ("onSiteWind", _) => a * siteToSourceConversions.gridUS
        case ("onSiteOther", _) => a * siteToSourceConversions.gridUS
        case ("offSite", _) => a * siteToSourceConversions.gridUS
        case (_, _) => throw new Exception("Could Not Convert to Source Energy")
      }}
      case None => throw new Exception("Could not read site energy entry")
    }
    return EnergyTuple(energyType,energyName,sourceEnergyValue)
  }

  /*def getParkingEnergy: Future[Energy] = Future {
    parameters.asOpt[Parking] match {
      case Some(Parking(a, b, c, d, e, units, "USA")) => {

        val openArea: Double = Area((a.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet
        val partiallyEnclosedParkingArea: Double = Area((b.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet
        val fullyEnclosedParkingArea: Double = Area((c.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet

        KBtus((9.385 * openArea) + (28.16 * partiallyEnclosedParkingArea) + (35.67 * fullyEnclosedParkingArea) +
          (0.009822 * (d.getOrElse(0.0) * e)))
      }
      case Some(Parking(a, b, c, d, e, units, "Canada")) => {

        val openArea: Double = Area((a.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet
        val partiallyEnclosedParkingArea: Double = Area((b.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet
        val fullyEnclosedParkingArea: Double = Area((c.getOrElse(0.0), units.getOrElse("ftSQ"))).get to SquareFeet

        KBtus((9.385 * openArea) + (28.16 * partiallyEnclosedParkingArea) + (35.67 * fullyEnclosedParkingArea) +
          (0.009822 * (d.getOrElse(0.0) * e))) in Gigajoules
      }
      case Some(_) => KBtus(0)
      case None => KBtus(0)
    }
  }*/

  def countryZero:Energy = {
    country match {
      case "USA" => KBtus(0)
      case _ => Gigajoules(0)
    }
  }

  def getPoolEnergy: Future[Energy] = Future {

    parameters.asOpt[Pool] match {
      case Some(Pool(Some("Indoor"), "USA", "K12School", Some("Recreational"))) => KBtus(1257300)
      case Some(Pool(Some("Indoor"), "USA", "K12School", Some("ShortCourse"))) => KBtus(2095500)
      case Some(Pool(Some("Indoor"), "USA", "K12School", Some("Olympic"))) => KBtus(6266009)
      case Some(Pool(Some("Indoor"), "USA", "Hotel", Some("Recreational"))) => KBtus(1010711)
      case Some(Pool(Some("Indoor"), "USA", "Hotel", Some("ShortCourse"))) => KBtus(1684518)
      case Some(Pool(Some("Indoor"), "USA", "Hotel", Some("Olympic"))) => KBtus(5037084)
      case Some(Pool(Some("Indoor"), "USA", _, Some("Recreational"))) => KBtus(853981)
      case Some(Pool(Some("Indoor"), "USA", _, Some("ShortCourse"))) => KBtus(1423301)
      case Some(Pool(Some("Indoor"), "USA", _, Some("Olympic"))) => KBtus(4255987)
      case Some(Pool(Some("Indoor"), "Canada", "K12School", Some("Recreational"))) => KBtus(1202780) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", "K12School", Some("ShortCourse"))) => KBtus(2004633) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", "K12School", Some("Olympic"))) => KBtus(5993047) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", "Hotel", Some("Recreational"))) => KBtus(962982) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", "Hotel", Some("ShortCourse"))) => KBtus(1604654) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", "Hotel", Some("Olympic"))) => KBtus(4799745) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", _, Some("Recreational"))) => KBtus(810384) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", _, Some("ShortCourse"))) => KBtus(1351587) in Gigajoules
      case Some(Pool(Some("Indoor"), "Canada", _, Some("Olympic"))) => KBtus(4040544) in Gigajoules


      case Some(Pool(Some("Outdoor"), "USA", _, Some("Recreational"))) => KBtus(119914)
      case Some(Pool(Some("Outdoor"), "USA", _, Some("ShortCourse"))) => KBtus(199857)
      case Some(Pool(Some("Outdoor"), "USA", _, Some("Olympic"))) => KBtus(597621)
      case Some(Pool(Some("Outdoor"), "Canada", _, Some("Recreational"))) => KBtus(112790) in Gigajoules
      case Some(Pool(Some("Outdoor"), "Canada", _, Some("ShortCourse"))) => KBtus(187668) in Gigajoules
      case Some(Pool(Some("Outdoor"), "Canada", _, Some("Olympic"))) => KBtus(561108) in Gigajoules

      case Some(Pool(_, _, _, _)) => KBtus(0)
      case None => KBtus(0)
    }
  }
}

case class  ConversionInfo(country:Option[String], reportingUnits: Option[String], buildingType:Option[String], postalCode:Option[String], state:Option[String], buildingName:Option[String])
object ConversionInfo {
  implicit val conversionInfoReads: Reads[ConversionInfo] = Json.reads[ConversionInfo]
}

case class EnergyMetrics(energyType:String,energyName:String,energyUnits:String,energyUse:Double,energyRate:Option[Double])
object EnergyMetrics {
  implicit val energyReads: Reads[EnergyMetrics] = (
    (JsPath \ "energyType").read[String] and
    (JsPath \ "energyName").read[String] and
    (JsPath \ "energyUnits").read[String] and
    (JsPath \ "energyUse").read[Double](min(0.0)) and
    (JsPath \ "energyRate").readNullable[Double]
    )(EnergyMetrics.apply _)
}

case class EnergyList(energies:List[EnergyMetrics])
object EnergyList {
  implicit val listReads:Reads[EnergyList] = Json.reads[EnergyList]
}

case class RenewableEnergyList(renewableEnergies:List[EnergyMetrics])
object RenewableEnergyList {
  implicit val listReads:Reads[RenewableEnergyList] = Json.reads[RenewableEnergyList]
}

object siteToSourceConversions {
  val gridUS:Double = 3.14
  val gridCanada:Double = 2.05
  val onSiteElectricity:Double = 1.00
  val ngUS:Double = 1.05
  val ngCanada:Double = 1.02
  val fuelOil:Double = 1.01 // 1,2,3,4,5,6 Diesel, Kerosene
  val propaneUS:Double = 1.01
  val propaneCanada:Double = 1.03
  val steam:Double = 1.2
  val hotWater:Double = 1.2
  val chilledWaterUS:Double = 1.0
  val chilledWaterCanada:Double = 0.71
  val wood:Double = 1.0
  val coal:Double = 1.0
  val coke:Double = 1.0
  val other:Double = 1.0

}

case class Pool(indoorOutdoor:Option[String],country:String, buildingType:String, poolType:Option[String])
object Pool {
  implicit val poolRead: Reads[Pool] = Json.reads[Pool]
}

/*case class Parking(openParkingArea:Option[Double],partiallyEnclosedParkingArea:Option[Double],
                   fullyEnclosedParkingArea:Option[Double], HDD:Option[Double], hasParkingHeating:Option[Boolean],
                   parkingAreaUnits:Option[String],country:String)
object Parking {
  implicit val parkingRead: Reads[Parking] = Json.reads[Parking]
}*/

case class EnergyTuple(energyType: String, energyName: String, energyValue: Energy)

