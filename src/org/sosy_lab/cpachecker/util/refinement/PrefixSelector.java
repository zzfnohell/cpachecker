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
package org.sosy_lab.cpachecker.util.refinement;

import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.cpachecker.util.variableclassification.VariableClassification;

public class PrefixSelector {

  private final ScorerFactory factory;
  private final Optional<VariableClassification> classification;

  public InfeasiblePrefix selectSlicedPrefix(List<PrefixPreference> pPrefixPreference,
      List<InfeasiblePrefix> pInfeasiblePrefixes) {
    return Ordering.compound(createComparators(pPrefixPreference)).min(pInfeasiblePrefixes);
  }

  public int obtainScoreForPrefixes(final List<InfeasiblePrefix> pPrefixes, final PrefixPreference pPreference) {

    int defaultScore = Integer.MAX_VALUE;

    if (!classification.isPresent()) {
      return defaultScore;
    }

    Scorer scorer = factory.createScorer(pPreference);
    return pPrefixes.stream().mapToInt(p -> scorer.computeScore(p)).min().orElse(defaultScore);
  }

  private List<Comparator<InfeasiblePrefix>> createComparators(List<PrefixPreference> pPrefixPreference) {
    return Lists.transform(pPrefixPreference, p -> factory.createScorer(p).getComparator());
  }

  public static final List<PrefixPreference> NO_SELECTION =
      Collections.singletonList(PrefixPreference.NONE);

  public enum PrefixPreference {

    // heuristics based on the length of the infeasible prefix
    LENGTH_MIN,
    LENGTH_MAX,

    // heuristics based on domain-type score over variables in the interpolant sequence
    DOMAIN_MIN,
    DOMAIN_MAX,

    // heuristics based on loop-counter variables referenced in the interpolant sequence
    LOOPS_MIN,
    LOOPS_MAX,

    // heuristics based on approximating the depth of the refinement root
    PIVOT_MIN,
    PIVOT_MAX,

    // heuristic based on the width of the interpolant sequence
    WIDTH_MIN,
    WIDTH_MAX,

    // heuristic based on counting the number of assignments over variables in the interpolant sequence
    ASSIGNMENTS_MIN,
    ASSIGNMENTS_MAX,

    // heuristic based on counting the number of assumptions over variables in the interpolant sequence
    ASSUMPTIONS_MIN,
    ASSUMPTIONS_MAX,

    // use this only if you are feeling lucky
    RANDOM,

    // signals to not perform any selection
    NONE
  }

  private static class ScorerFactory {

    private final Optional<VariableClassification> classification;
    private final Optional<LoopStructure> loopStructure;

    // We instantiate this only once, because it is pseudo-random (i.e., deterministic),
    // and if we instantiate it every time, we get the same sequence of random numbers.
    private final Scorer randomScorer = new RandomScorer();

    public ScorerFactory(final Optional<VariableClassification> pClassification,
        final Optional<LoopStructure> pLoopStructure) {
      classification = pClassification;
      loopStructure = pLoopStructure;
    }

    public Scorer createScorer(PrefixPreference pPreference) {
      switch (pPreference) {
        case LENGTH_MIN:
          return new LengthScorer();
        case LENGTH_MAX:
          return new LengthScorer().invert();
        case DOMAIN_MIN:
          return new DomainScorer(classification, loopStructure);
        case DOMAIN_MAX:
          return new DomainScorer(classification, loopStructure).invert();
        case LOOPS_MIN:
          return new LoopScorer(classification, loopStructure);
        case LOOPS_MAX:
          return new LoopScorer(classification, loopStructure).invert();
        case WIDTH_MIN:
          return new WidthScorer();
        case WIDTH_MAX:
          return new WidthScorer().invert();
        case PIVOT_MIN:
          return new DepthScorer();
        case PIVOT_MAX:
          return new DepthScorer().invert();
        case ASSIGNMENTS_MIN:
          return new AssignmentScorer(classification);
        case ASSIGNMENTS_MAX:
          return new AssignmentScorer(classification).invert();
        case ASSUMPTIONS_MIN:
          return new AssumptionScorer(classification);
        case ASSUMPTIONS_MAX:
          return new AssumptionScorer(classification).invert();
        case RANDOM:
          return randomScorer;

        // illegal arguments
        case NONE:
        default:
          throw new IllegalArgumentException("Illegal prefix preference " + pPreference + " given!");
      }
    }
  }

  private interface Scorer {

    int computeScore(final InfeasiblePrefix pPrefix);

    default Comparator<InfeasiblePrefix> getComparator() {
      return Comparator.comparingInt(this::computeScore);
    }

    default Scorer invert() {
      Scorer delegate = this;
      return new Scorer() {
        @Override
        public int computeScore(InfeasiblePrefix pPrefix) {
          int score = delegate.computeScore(pPrefix);
          if (score == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
          }
          return -score;
        }
      };
    }
  }

  private static class DomainScorer implements Scorer {

    private final Optional<VariableClassification> classification;
    private final Optional<LoopStructure> loopStructure;

    public DomainScorer(final Optional<VariableClassification> pClassification,
        final Optional<LoopStructure> pLoopStructure) {
      classification = pClassification;
      loopStructure = pLoopStructure;
    }

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      return classification.get().obtainDomainTypeScoreForVariables(pPrefix.extractSetOfIdentifiers(), loopStructure);
    }
  }

  private static class LoopScorer implements Scorer {

    private final Optional<VariableClassification> classification;
    private final Optional<LoopStructure> loopStructure;

    public LoopScorer(final Optional<VariableClassification> pClassification,
        final Optional<LoopStructure> pLoopStructure) {
      classification = pClassification;
      loopStructure = pLoopStructure;
    }

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      int score = classification.get().obtainDomainTypeScoreForVariables(pPrefix.extractSetOfIdentifiers(), loopStructure);

      if(score != Integer.MAX_VALUE) {
        score = 0;
      }

      return score;
    }
  }

  private static class WidthScorer implements Scorer {

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      return pPrefix.getNonTrivialLength();
    }
  }

  private static class DepthScorer implements Scorer {

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      return pPrefix.getDepthOfPivotState();
    }
  }

  private static class LengthScorer implements Scorer {

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      return pPrefix.getPath().size();
    }
  }

  private static class AssignmentScorer implements Scorer {

    private final Optional<VariableClassification> classification;

    public AssignmentScorer(final Optional<VariableClassification> pClassification) {
      classification = pClassification;
    }

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      int count = 0;
      for (String variable : pPrefix.extractSetOfIdentifiers()) {
        count = count + classification.get().getAssignedVariables().count(variable);
      }

      return count;
    }
  }

  private static class AssumptionScorer implements Scorer {

    private final Optional<VariableClassification> classification;

    public AssumptionScorer(final Optional<VariableClassification> pClassification) {
      classification = pClassification;
    }

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      int count = 0;
      for (String variable : pPrefix.extractSetOfIdentifiers()) {
        count = count + classification.get().getAssumedVariables().count(variable);
      }

      return count;
    }
  }

  private static class RandomScorer implements Scorer {

    private static final Random random = new Random(0);

    @Override
    public int computeScore(final InfeasiblePrefix pPrefix) {
      return random.nextInt(1000);
    }
  }

  public PrefixSelector(Optional<VariableClassification> pClassification,
                             Optional<LoopStructure> pLoopStructure) {
    factory = new ScorerFactory(pClassification, pLoopStructure);
    classification  = pClassification;
  }
}
