/*
 * Copyright 2011-2017 Chris de Vreeze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cdevreeze.tqa.console

import java.io.File
import java.net.URI
import java.util.logging.Logger

import scala.reflect.classTag

import eu.cdevreeze.tqa.backingelem.DocumentBuilder
import eu.cdevreeze.tqa.backingelem.indexed.IndexedDocumentBuilder
import eu.cdevreeze.tqa.backingelem.nodeinfo.SaxonDocumentBuilder
import eu.cdevreeze.tqa.relationship.DefaultRelationshipFactory
import eu.cdevreeze.tqa.relationship.DimensionalRelationship
import eu.cdevreeze.tqa.taxonomybuilder.DefaultDtsCollector
import eu.cdevreeze.tqa.taxonomybuilder.TaxonomyBuilder
import eu.cdevreeze.yaidom.parse.DocumentParserUsingStax
import net.sf.saxon.s9api.Processor

/**
 * Program that shows dimensional data in a given taxonomy.
 *
 * @author Chris de Vreeze
 */
object ShowDimensions {

  private val logger = Logger.getGlobal

  def main(args: Array[String]): Unit = {
    require(args.size >= 2, s"Usage: ShowDimensions <taxo root dir> <entrypoint URI 1> ...")
    val rootDir = new File(args(0))
    require(rootDir.isDirectory, s"Not a directory: $rootDir")

    val entrypointUris = args.drop(1).map(u => URI.create(u)).toSet

    val useSaxon = System.getProperty("useSaxon", "false").toBoolean

    val documentBuilder = getDocumentBuilder(useSaxon, rootDir)
    val documentCollector = DefaultDtsCollector(entrypointUris)

    val lenient = System.getProperty("lenient", "false").toBoolean

    val relationshipFactory =
      if (lenient) DefaultRelationshipFactory.LenientInstance else DefaultRelationshipFactory.StrictInstance

    val taxoBuilder =
      TaxonomyBuilder.
        withDocumentBuilder(documentBuilder).
        withDocumentCollector(documentCollector).
        withRelationshipFactory(relationshipFactory)

    logger.info(s"Starting building the DTS with entrypoint(s) ${entrypointUris.mkString(", ")}")

    val basicTaxo = taxoBuilder.build()

    val rootElems = basicTaxo.taxonomyBase.rootElems

    logger.info(s"The taxonomy has ${rootElems.size} taxonomy root elements")
    logger.info(s"The taxonomy has ${basicTaxo.relationships.size} relationships")
    logger.info(s"The taxonomy has ${basicTaxo.findAllDimensionalRelationshipsOfType(classTag[DimensionalRelationship]).size} dimensional relationships")

    val hasHypercubes = basicTaxo.findAllHasHypercubeRelationships

    val hasHypercubeGroupsWithSameKey = hasHypercubes.distinct.groupBy(hh => (hh.primary, hh.elr)).filter(_._2.size >= 2)

    logger.info(s"Number of has_hypercube groups with more than 1 has-hypercube for the same primary and ELR: ${hasHypercubeGroupsWithSameKey.size}")

    hasHypercubes foreach { hasHypercube =>
      println(s"Has-hypercube found for primary ${hasHypercube.primary} and ELR ${hasHypercube.elr}")

      val hypercubeDimensions = basicTaxo.filterOutgoingHypercubeDimensionRelationships(hasHypercube.hypercube)(rel => hasHypercube.isFollowedBy(rel))
      println(s"It has dimensions: ${hypercubeDimensions.map(_.dimension).distinct.sortBy(_.toString).mkString("\n\t", ",\n\t", "")}")
    }

    // Concrete primary items and inheritance of has-hypercubes

    val concretePrimaryItemDecls = basicTaxo.findAllPrimaryItemDeclarations.filter(e => !e.globalElementDeclaration.isAbstract)

    val concretePrimariesNotInheritingOrHavingHasHypercube = concretePrimaryItemDecls filter { itemDecl =>
      basicTaxo.findAllOwnOrInheritedHasHypercubes(itemDecl.targetEName).isEmpty
    } map (_.targetEName)

    val namespacesOfConcretePrimariesNotInheritingOrHavingHasHypercube =
      concretePrimariesNotInheritingOrHavingHasHypercube.flatMap(_.namespaceUriOption).distinct.sortBy(_.toString)

    logger.info(s"Number of (in total ${concretePrimaryItemDecls.size}) concrete primary items not inheriting or having any has-hypercube: ${concretePrimariesNotInheritingOrHavingHasHypercube.distinct.size}")
    logger.info(s"Namespaces of concrete primary items not inheriting or having any has-hypercube (first 50):\n\t${namespacesOfConcretePrimariesNotInheritingOrHavingHasHypercube.take(50).mkString(", ")}")

    val inheritingPrimaries =
      (hasHypercubes flatMap { hh =>
        basicTaxo.filterLongestOutgoingConsecutiveDomainMemberRelationshipPaths(hh.primary) { path =>
          path.firstRelationship.elr == hh.elr
        }
      } flatMap (_.relationships.map(_.member))).toSet

    val ownPrimaries = hasHypercubes.map(_.sourceConceptEName).toSet

    val inheritingOrOwnPrimaries = inheritingPrimaries.union(ownPrimaries)

    val inheritingOrOwnConcretePrimaries =
      inheritingOrOwnPrimaries filter (item => basicTaxo.findPrimaryItemDeclaration(item).exists(e => !e.globalElementDeclaration.isAbstract))

    require(
      concretePrimariesNotInheritingOrHavingHasHypercube.toSet == concretePrimaryItemDecls.map(_.targetEName).toSet.diff(inheritingOrOwnConcretePrimaries),
      s"Finding concrete primary items not inheriting or having any has-hypercube from 2 directions must give the same result")

    logger.info("Showing inheriting concrete items")

    concretePrimaryItemDecls.map(_.targetEName).distinct.sortBy(_.toString) foreach { item =>
      val hasHypercubes = basicTaxo.findAllOwnOrInheritedHasHypercubes(item)
      val elrPrimariesPairs = hasHypercubes.groupBy(_.elr).mapValues(_.map(_.primary)).toSeq.sortBy(_._1)

      elrPrimariesPairs foreach {
        case (elr, primaries) =>
          println(s"Concrete item $item inherits or has has-hypercubes for ELR $elr and with primaries ${primaries.distinct.sortBy(_.toString).mkString(", ")}")
      }
    }

    logger.info("Ready")
  }

  private def uriToLocalUri(uri: URI, rootDir: File): URI = {
    // Not robust
    val relativePath = uri.getScheme match {
      case "http"  => uri.toString.drop("http://".size)
      case "https" => uri.toString.drop("https://".size)
      case _       => sys.error(s"Unexpected URI $uri")
    }

    val f = new File(rootDir, relativePath.dropWhile(_ == '/'))
    f.toURI
  }

  private def getDocumentBuilder(useSaxon: Boolean, rootDir: File): DocumentBuilder = {
    if (useSaxon) {
      val processor = new Processor(false)

      new SaxonDocumentBuilder(processor.newDocumentBuilder(), uriToLocalUri(_, rootDir))
    } else {
      new IndexedDocumentBuilder(DocumentParserUsingStax.newInstance(), uriToLocalUri(_, rootDir))
    }
  }
}