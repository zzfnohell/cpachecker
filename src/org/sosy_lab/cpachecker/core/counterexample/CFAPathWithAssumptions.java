/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.counterexample;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ForwardingList;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.core.counterexample.ConcreteStatePath.ConcreteStatePathNode;
import org.sosy_lab.cpachecker.core.counterexample.ConcreteStatePath.IntermediateConcreteState;
import org.sosy_lab.cpachecker.core.counterexample.ConcreteStatePath.SingleConcreteState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysisWithConcreteCex;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.path.PathIterator;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.predicates.PathChecker;

/**
 * This class represents a path of cfaEdges, that contain the additional Information
 * at which edge which assignableTerm was created when this path was checked by
 * the class {@link PathChecker}.
 *
 */
public class CFAPathWithAssumptions extends ForwardingList<CFAEdgeWithAssumptions> {

  private final ImmutableList<CFAEdgeWithAssumptions> pathWithAssignments;

  private CFAPathWithAssumptions(
      List<CFAEdgeWithAssumptions> pPathWithAssignments) {
    pathWithAssignments = ImmutableList.copyOf(pPathWithAssignments);
  }

  public static CFAPathWithAssumptions empty() {
    return new CFAPathWithAssumptions(ImmutableList.<CFAEdgeWithAssumptions>of());
  }

  @Override
  protected List<CFAEdgeWithAssumptions> delegate() {
    return pathWithAssignments;
  }

  boolean fitsPath(List<CFAEdge> pPath) {
    int index = 0;
    Iterator<CFAEdge> it = pPath.iterator();

    while (it.hasNext()) {
      CFAEdge edge = it.next();
      CFAEdgeWithAssumptions cfaWithAssignment = pathWithAssignments.get(index);

      if (!edge.equals(cfaWithAssignment.getCFAEdge())) {
        return false;
      }
      index++;
    }

    return true;
  }

  public Multimap<ARGState, CFAEdgeWithAssumptions> getExactVariableValues(ARGPath pPath) {
    Multimap<ARGState, CFAEdgeWithAssumptions> result = HashMultimap.create();

    PathIterator pathIterator = pPath.fullPathIterator();
    int multiEdgeOffset = 0;

    while (pathIterator.hasNext()) {
      CFAEdgeWithAssumptions edgeWithAssignment = pathWithAssignments.get(pathIterator.getIndex() + multiEdgeOffset);
      CFAEdge argPathEdge = pathIterator.getOutgoingEdge();

      if (!edgeWithAssignment.getCFAEdge().equals(argPathEdge)) {
        // path is not equivalent
        return ImmutableMultimap.of();
      }

      final ARGState abstractState;
      if (pathIterator.isPositionWithState()) {
        abstractState = pathIterator.getAbstractState();
      } else {
        abstractState = pathIterator.getPreviousAbstractState();
      }
      result.put(abstractState, edgeWithAssignment);

      pathIterator.advance();
    }
    // last state is ignored

    return result;
  }

  public static CFAPathWithAssumptions of(ConcreteStatePath statePath,
      AssumptionToEdgeAllocator pAllocator) {

    List<CFAEdgeWithAssumptions> result = new ArrayList<>(statePath.size());
    List<IntermediateConcreteState> currentIntermediateStates = new ArrayList<>();

    for (ConcreteStatePathNode node : statePath) {
      CFAEdgeWithAssumptions edge;

      // this is an intermediate state: just create the assumptions for it
      // and add it as if it was a normal edge
      if (node instanceof IntermediateConcreteState) {
        IntermediateConcreteState intermediateState = (IntermediateConcreteState) node;
        currentIntermediateStates.add(intermediateState);
        edge =
            pAllocator.allocateAssumptionsToEdge(
                intermediateState.getCfaEdge(), intermediateState.getConcreteState());

      } else {
        SingleConcreteState singleState = (SingleConcreteState) node;

        // no ARG hole, just a normal edge
        if (currentIntermediateStates.isEmpty()) {
          edge =
              pAllocator.allocateAssumptionsToEdge(
                  singleState.getCfaEdge(), singleState.getConcreteState());

          /* End of an ARG hole, handle all the intermediate edges before
           * and create the assumptions at the end of the (dynamic) multi edge
           * for all changed variables.Since it is impossible to properly project
           * the assumption from the assumptions of the edges in the multi edge,
           * due to aliasing, simply create assumptions for all edges with the concrete state
           * of the last edge, thus correctly projecting all lvalues at the end of the multi edge.*/
        } else {
          Set<AExpressionStatement> assumptions = new HashSet<>();
          Set<String> assumptionCodes = new HashSet<>();
          ConcreteState lastState = singleState.getConcreteState();

          StringBuilder comment = new StringBuilder("");

          if (!isEmptyDeclaration(singleState.getCfaEdge())) {
            for (IntermediateConcreteState intermediates : currentIntermediateStates) {
              CFAEdgeWithAssumptions assumptionForedge =
                  pAllocator.allocateAssumptionsToEdge(intermediates.getCfaEdge(), lastState);
              addAssumptionsIfNecessary(assumptions, assumptionCodes, comment, assumptionForedge);
            }

            // add assumptions for last edge if necessary
            addAssumptionsIfNecessary(
                assumptions,
                assumptionCodes,
                comment,
                pAllocator.allocateAssumptionsToEdge(singleState.getCfaEdge(), lastState));
          }

          // Finally create Last edge and multi edge
          edge =
              new CFAEdgeWithAssumptions(
                  singleState.getCfaEdge(), new ArrayList<>(assumptions), comment.toString());

          // remove all handled intermediate states
          currentIntermediateStates.clear();
        }
      }

      // add created edge to result
      result.add(edge);
    }

    return new CFAPathWithAssumptions(result);
  }

  private static boolean isEmptyDeclaration(CFAEdge pCfaEdge) {
    if (pCfaEdge instanceof ADeclarationEdge) {
      ADeclarationEdge declarationEdge = (ADeclarationEdge) pCfaEdge;
      ADeclaration declaration = declarationEdge.getDeclaration();
      if (declaration instanceof AVariableDeclaration) {
        AVariableDeclaration variableDeclaration = (AVariableDeclaration) declaration;
        return variableDeclaration.getInitializer() == null && !variableDeclaration.isGlobal();
      }
      return true;
    }
    return false;
  }

  private static void addAssumptionsIfNecessary(Set<AExpressionStatement> assumptions, Set<String> assumptionCodes,
      StringBuilder comment, CFAEdgeWithAssumptions lastIntermediate) {
    // throw away redundant assumptions
    for (AExpressionStatement assumption : lastIntermediate.getExpStmts()) {
      if (!assumptionCodes.contains(assumption.toASTString())) {
        assumptions.add(assumption);
        assumptionCodes.add(assumption.toASTString());
      }
    }

    String commentOfEdge = lastIntermediate.getComment();

    if (!isNullOrEmpty(commentOfEdge)) {
      comment.append(commentOfEdge);
      comment.append("\n");
    }
  }

  public Optional<CFAPathWithAssumptions> mergePaths(CFAPathWithAssumptions pOtherPath) {

    if (pOtherPath.size() != this.size()) {
      return Optional.empty();
    }

    List<CFAEdgeWithAssumptions> result = new ArrayList<>(size());
    Iterator<CFAEdgeWithAssumptions> path2Iterator = iterator();

    for (CFAEdgeWithAssumptions edge : this) {
      CFAEdgeWithAssumptions other = path2Iterator.next();
      if (!edge.getCFAEdge().equals(other.getCFAEdge())) {
        return Optional.empty();
      }
      CFAEdgeWithAssumptions resultEdge = edge.mergeEdge(other);
      result.add(resultEdge);
    }

    return Optional.of(new CFAPathWithAssumptions(result));
  }

  public static CFAPathWithAssumptions of(
      ARGPath pPath,
      ConfigurableProgramAnalysis pCPA,
      AssumptionToEdgeAllocator pAssumptionToEdgeAllocator) {

    FluentIterable<ConfigurableProgramAnalysisWithConcreteCex> cpas =
        CPAs.asIterable(pCPA).filter(ConfigurableProgramAnalysisWithConcreteCex.class);

    Optional<CFAPathWithAssumptions> result = Optional.empty();

    for (ConfigurableProgramAnalysisWithConcreteCex wrappedCpa : cpas) {
      ConcreteStatePath path = wrappedCpa.createConcreteStatePath(pPath);
      CFAPathWithAssumptions cexPath = CFAPathWithAssumptions.of(path, pAssumptionToEdgeAllocator);

      if (result.isPresent()) {
        result = result.get().mergePaths(cexPath);
        // If there were conflicts during merging, stop
        if (!result.isPresent()) {
          break;
        }
      } else {
        result = Optional.of(cexPath);
      }
    }

    if (!result.isPresent()) {
      return CFAPathWithAssumptions.empty();
    } else {
      return result.get();
    }
  }
}