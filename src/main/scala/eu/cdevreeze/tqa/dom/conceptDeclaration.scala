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

package eu.cdevreeze.tqa.dom

import java.net.URI

import eu.cdevreeze.tqa.ENames.XbrldtDimensionItemEName
import eu.cdevreeze.tqa.ENames.XbrldtHypercubeItemEName
import eu.cdevreeze.tqa.ENames.XbrldtTypedDomainRefEName
import eu.cdevreeze.tqa.ENames.XbrliItemEName
import eu.cdevreeze.tqa.ENames.XbrliTupleEName
import eu.cdevreeze.yaidom.core.EName

/**
 * Concept declaration, wrapping a GlobalElementDeclaration. It must be in substitution group xbrli:item or xbrli:tuple,
 * either directly or indirectly.
 *
 * There are no sub-classes for dimension members, because as global element declarations they are not defined in the Dimensions specification.
 *
 * @author Chris de Vreeze
 */
sealed abstract class ConceptDeclaration(val globalElementDeclaration: GlobalElementDeclaration) extends AnyTaxonomyElem {

  final def targetEName: EName = {
    globalElementDeclaration.targetEName
  }

  final override def equals(other: Any): Boolean = other match {
    case other: ConceptDeclaration => globalElementDeclaration == other.globalElementDeclaration
    case _                         => false
  }

  final override def hashCode: Int = {
    globalElementDeclaration.hashCode
  }
}

/**
 * Item declaration. It must be in the xbrli:item substitution group, directly or indirectly.
 */
sealed class ItemDeclaration(globalElementDeclaration: GlobalElementDeclaration) extends ConceptDeclaration(globalElementDeclaration)

/**
 * Tuple declaration. It must be in the xbrli:tuple substitution group, directly or indirectly.
 */
final class TupleDeclaration(globalElementDeclaration: GlobalElementDeclaration) extends ConceptDeclaration(globalElementDeclaration)

/**
 * Primary item declaration. It must be in the xbrli:item substitution group but neither in the xbrldt:hypercubeItem nor in the xbrldt:dimensionItem substitution groups.
 *
 * A primary item may be used as explicit dimension member.
 *
 * Note that in the Dimensions specification, primary item declarations and domain-member declarations have exactly the same
 * definition! Although in a taxonomy the dimensional relationships make clear whether an item plays the role of primary item
 * or of domain-member, here we call each such item declaration a primary item declaration.
 */
final class PrimaryItemDeclaration(globalElementDeclaration: GlobalElementDeclaration) extends ItemDeclaration(globalElementDeclaration)

/**
 * Hypercube declaration. It must be an abstract item declaration in the xbrldt:hypercubeItem substitution group.
 */
final class HypercubeDeclaration(globalElementDeclaration: GlobalElementDeclaration) extends ItemDeclaration(globalElementDeclaration) {

  def hypercubeEName: EName = {
    targetEName
  }
}

/**
 * Dimension declaration. It must be an abstract item declaration in the xbrldt:dimensionItem substitution group.
 */
sealed abstract class DimensionDeclaration(globalElementDeclaration: GlobalElementDeclaration) extends ItemDeclaration(globalElementDeclaration) {

  final def isTyped: Boolean = {
    globalElementDeclaration.attributeOption(XbrldtTypedDomainRefEName).isDefined
  }

  final def dimensionEName: EName = {
    targetEName
  }
}

/**
 * Explicit dimension. It must be a dimension declaration without attribute xbrldt:typedDomainRef, among other requirements.
 */
final class ExplicitDimension(globalElementDeclaration: GlobalElementDeclaration) extends DimensionDeclaration(globalElementDeclaration) {
  require(!isTyped, s"${globalElementDeclaration.targetEName} is typed and therefore not an explicit dimension")
}

/**
 * Typed dimension. It must be a dimension declaration with an attribute xbrldt:typedDomainRef, among other requirements.
 */
final class TypedDimension(globalElementDeclaration: GlobalElementDeclaration) extends DimensionDeclaration(globalElementDeclaration) {
  require(isTyped, s"${globalElementDeclaration.targetEName} is not typed and therefore not a typed dimension")

  /**
   * Returns the value of the xbrldt:typedDomainRef attribute, as absolute (!) URI.
   */
  def typedDomainRef: URI = {
    val rawUri = URI.create(globalElementDeclaration.attribute(XbrldtTypedDomainRefEName))
    globalElementDeclaration.backingElem.baseUri.resolve(rawUri)
  }
}

object ConceptDeclaration {

  /**
   * Builder of ConceptDeclaration objects, given a mapping of substitution groups to their "parent" substitution group.
   * The xbrli:item and xbrli:tuple substitution groups must occur as mapped values in the map, or else no concept declaration
   * will be created.
   */
  final class Builder(val knownSubstitutionGroups: Map[EName, EName]) {

    def optConceptDeclaration(elemDecl: GlobalElementDeclaration): Option[ConceptDeclaration] = {
      val isHypercube = hasSubstitutionGroup(elemDecl, XbrldtHypercubeItemEName)
      val isDimension = hasSubstitutionGroup(elemDecl, XbrldtDimensionItemEName)
      val isItem = hasSubstitutionGroup(elemDecl, XbrliItemEName)
      val isTuple = hasSubstitutionGroup(elemDecl, XbrliTupleEName)

      require(!isItem || !isTuple, s"A concept (${elemDecl.targetEName}) cannot be both an item and tuple")
      require(!isHypercube || !isDimension, s"A concept (${elemDecl.targetEName}) cannot be both a hypercube and dimension")
      require(isItem || !isHypercube, s"A concept (${elemDecl.targetEName}) cannot be a hypercube but not an item")
      require(isItem || !isDimension, s"A concept (${elemDecl.targetEName}) cannot be a dimension but not an item")

      if (isTuple) {
        Some(new TupleDeclaration(elemDecl))
      } else if (isItem) {
        if (isHypercube) {
          Some(new HypercubeDeclaration(elemDecl))
        } else if (isDimension) {
          if (elemDecl.attributeOption(XbrldtTypedDomainRefEName).isDefined) Some(new TypedDimension(elemDecl)) else Some(new ExplicitDimension(elemDecl))
        } else {
          Some(new PrimaryItemDeclaration(elemDecl))
        }
      } else {
        None
      }
    }

    def hasSubstitutionGroup(elemDecl: GlobalElementDeclaration, substGroup: EName): Boolean = {
      (elemDecl.substitutionGroupOption == Some(substGroup)) || {
        val derivedSubstGroups = knownSubstitutionGroups.filter(_._2 == substGroup).keySet

        // Recursive calls

        derivedSubstGroups.exists(substGrp => hasSubstitutionGroup(elemDecl, substGrp))
      }
    }
  }
}
