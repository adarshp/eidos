package org.clulab.wm.eidos.context

import org.clulab.wm.eidos.utils.Sourcer
import org.deeplearning4j.nn.graph.ComputationGraph
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport
import org.nd4j.linalg.factory.Nd4j

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object GeoDisambiguateParser {
  val I_LOC = 0
  val B_LOC = 1
  val O_LOC = 2
  val UNKNOWN_TOKEN = "UNKNOWN_TOKEN"
  val TIME_DISTRIBUTED_1 = "time_distributed_1"
}

class GeoDisambiguateParser(modelPath: String, word2IdxPath: String, loc2geonameIDPath: String) {

  protected val network: ComputationGraph = KerasModelImport.importKerasModelAndWeights(modelPath, false)

  lazy protected val word2int: mutable.Map[String, Int] = readDict(word2IdxPath)
  lazy protected val loc2geonameID: mutable.Map[String, Int] = readDict(loc2geonameIDPath) // provide path of geoname dict file having geonameID with max population

  protected def readDict(dictPath: String): mutable.Map[String, Int] = {
    val source = Sourcer.sourceFromResource(dictPath)
    val dict = mutable.Map.empty[String, Int]

    source.getLines.foreach { line =>
      val words = line.split(' ')

      dict += (words(0).toString -> words(1).toInt)
    }
    source.close()
    dict
  }

  def makeFeatures(words: Array[String]): Array[Float] = {
    val unknown = word2int(GeoDisambiguateParser.UNKNOWN_TOKEN)
    val features = words.map(word2int.getOrElse(_, unknown).toFloat)

    features
  }

  def makeLabels(features: Array[Float]): Array[Int] = {

    def argmax(values: Array[Float]): Int = {
      // This goes through the values twice, but at least it doesn't create extra objects.
      val max = values.max

      values.indexWhere(_ == max)
    }

    val input = Nd4j.create(features)
    val results = this.synchronized {
      network.setInput(0, input)
      network.feedForward()
    }
    val output = results.get(GeoDisambiguateParser.TIME_DISTRIBUTED_1)
    val predictions: Array[Array[Float]] =
        if (output.shape()(0) == 1) Array(output.toFloatVector)
        else output.toFloatMatrix

    predictions.map(prediction => argmax(prediction))
  }

  def makeGeoLocations(labelIndexes: Array[Int], words: Array[String],
      startOffsets: Array[Int], endOffsets: Array[Int]): List[GeoPhraseID] = {

    def newGeoPhraseID(startIndex: Int, endIndex: Int): GeoPhraseID = {
      val sliceWords = words.slice(startIndex, endIndex)
      val prettyLocationPhrase = words.mkString(" ")
      val locationPhrase = prettyLocationPhrase.replace(' ', '_').toLowerCase
      val geoNameId = loc2geonameID.get(locationPhrase)

      GeoPhraseID(prettyLocationPhrase, geoNameId, startOffsets(startIndex), endOffsets(endIndex))
    }

    val startIndexes = labelIndexes.indices.filter { index =>
      val labelIndex = labelIndexes(index)

      labelIndex == GeoDisambiguateParser.B_LOC || ( // beginning of a location
        labelIndex == GeoDisambiguateParser.I_LOC && ( // inside of a location if
            index == 0 || // labels start immediately on I_LOC
            labelIndexes(index - 1) == GeoDisambiguateParser.O_LOC // or without B_LOC or I_LOC preceeding
        )
      )
    }
    val result = startIndexes.map { startIndex =>
      val endIndex = labelIndexes.indexOf(GeoDisambiguateParser.O_LOC, startIndex + 1)

      // If not found, endIndex == -1, then use one off the end
      newGeoPhraseID(startIndex, if (endIndex == -1) labelIndexes.size else endIndex)
    }

    result.toList
  }
}
