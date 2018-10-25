package org.clulab.wm.eidos.document

import java.time.LocalDateTime

import org.clulab.processors.Document
import org.clulab.processors.Sentence
import org.clulab.processors.corenlp.CoreNLPDocument
import org.clulab.timenorm.TemporalCharbasedParser
import org.clulab.timenorm.formal.Interval
import org.clulab.wm.eidos.context.Geo_disambiguate_parser

import scala.collection.mutable.ListBuffer

class EidosDocument(sentences: Array[Sentence], text: Option[String]) extends CoreNLPDocument(sentences) {
  // TODO: @transient here means these values aren't serialized, which sort of defeats the purpose of serialization.
  // Currently no test checks to see if the values are preserved across serialization, but that doesn't make it right.
  @transient val times = new Array[List[TimeInterval]](sentences.length)
  @transient val geolocs = new Array[List[GeoPhraseID]](sentences.length)

  protected var anchor: Option[DCT] = None

  protected def parseFakeTime(): Unit = times.indices.foreach(times(_) = List[TimeInterval]())

  protected def parseRealTime(timenorm: TemporalCharbasedParser): Unit = {
    times.indices.foreach { index =>
      times(index) =
        if (this.sentences(index).entities.get.contains("DATE")) {
          val sentence_text = text match {
            case Some(t) => t.slice(this.sentences(index).startOffsets(0), this.sentences(index).endOffsets.last)
            case _ => this.sentences(index).getSentenceText
          }
          val intervals = if (this.anchor.isDefined)
            timenorm.intervals(timenorm.parse(sentence_text), Some(this.anchor.get.interval))
          else
            timenorm.intervals(timenorm.parse(sentence_text))
          // Sentences use offsets into the document.  Timenorm only knows about the single sentence.
          // Account for this by adding the starting offset of the first word of sentence.
          val offset = this.sentences(index).startOffsets(0)

          // Update  norms with B-I time expressions
          val norms = for (
            ((start, end), norm) <- this.sentences(index).startOffsets zip this.sentences(index).endOffsets zip this.sentences(index).norms.get;
            val inTimex = intervals.map(interval => (start - (interval._1._1 + offset), (interval._1._2 + offset) - end)).filter(x => x._1 >= 0 && x._2 >= 0)
          ) yield {
            inTimex.isEmpty match {
              case false if inTimex(0)._1 == 0 => "B-Time"
              case false if inTimex(0)._1 != 0 => "I-Time"
              case _ => norm
            }
          }
          this.sentences(index).norms = Some(norms.toArray)

          intervals.map { interval =>
            new TimeInterval((interval._1._1 + offset, interval._1._2 + offset), interval._2, sentence_text.slice(interval._1._1, interval._1._2))
          }
        }
      else
          List()
    }
  }

  def parseDCT(timenorm: Option[TemporalCharbasedParser], documentCreationTime:Option[String]): Unit = {
    if (timenorm.isDefined && documentCreationTime.isDefined)
      this.anchor = Some(new DCT(timenorm.get.dct(timenorm.get.parse(documentCreationTime.get)), documentCreationTime.get))
  }

  def getDCT(): Option[DCT] = this.anchor

  def parseTime(timenorm: Option[TemporalCharbasedParser]): Unit =
     if (timenorm.isDefined) parseRealTime(timenorm.get)
     else parseFakeTime()

  def parseGeoNorm(geo_disambiguate : Geo_disambiguate_parser): Unit = {
    geolocs.indices.foreach { index =>
      geolocs(index) = {
        val sentence_text = text match {
          case Some(t) => t.slice(this.sentences(index).startOffsets(0), this.sentences(index).endOffsets.last)
          case _ => this.sentences(index).getSentenceText
        }

        // val phrases_geoIDs = geo_disambiguate.parse(sentence_text) // location_phrases_GeonameID
        //val token_labels = geo_disambiguate.generate_NER_labels(geo_disambiguate.create_word_input(sentence_text)._1)
        val token_labels = geo_disambiguate.generate_NER_labels(geo_disambiguate.create_word_input(this.sentences(index).getSentenceText)._1)
        val offset = this.sentences(index).startOffsets(0)

        println("our output matrix looks like: ", token_labels.toString)
        // println("our output phrases looks like: ", phrases_geoID_all.toString)

        // Sentences use offsets into the document.  Timenorm only knows about the single sentence.
        // Account for this by adding the starting offset of the first word of sentence.
        // Update  norms with B-I time expressions

        val norms = for (((start, end), (norm, norm_index)) <- this.sentences(index).startOffsets zip this.sentences(index).endOffsets zip this.sentences(index).norms.get.zipWithIndex)

            yield{

              if (token_labels(norm_index) == "O") {
                (norm,start, end)
              }
              else {
                ("LOC", start, end) // token_labels(norm_index)
              }
          }

        println(norms.foreach(println))
        this.sentences(index).norms = Some(norms.map(_._1).toArray)  // Updating the norms here

        val phrases_geoID_all =  geo_disambiguate.get_complete_location_phrase( token_labels, geo_disambiguate.create_word_input(this.sentences(index).getSentenceText)._2,
                                 geo_disambiguate.loc2geonameID,norms.map(_._2), norms.map(_._3))



        phrases_geoID_all.toList.map(g => new GeoPhraseID(g._1, g._2, g._3, g._4))
        // phrases_geoID_all._2.toList.map(new GeoPhraseID(_))

      }
      List()
    }

  }

//  def parseTime(timenorm: Option[TemporalCharbasedParser]): Unit =
//    if (timenorm.isDefined) parseRealTime(timenorm.get)
//    else parseFakeTime()

  def parseGeoNorm_flag(geo_disambiguate: Option[Geo_disambiguate_parser]): Unit =
    if (geo_disambiguate.isDefined) parseGeoNorm(geo_disambiguate.get)
    // else parseFakeTime()

}

object EidosDocument {

  def apply(document: Document, keepText: Boolean = true): EidosDocument = {
    val text = document.text // This will be the preprocessed text now.
    // This constructor does not make use of the text,
    val eidosDocument = new EidosDocument(document.sentences, text)
    // so it must be set afterwards, if specified.
    if (keepText)
      eidosDocument.text = text
    eidosDocument
  }
}

class TimeInterval(val span: (Int, Int), val intervals: List[(LocalDateTime, LocalDateTime, Long)], val text: String)
class DCT(val interval: Interval, val text: String)

class GeoPhraseID(val phraseID: String, val  PhraseGeoID: String, val StartOffset_locs: Int, val EndOffset_locs: Int)
