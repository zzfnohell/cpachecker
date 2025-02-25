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
package org.sosy_lab.cpachecker.cpa.composite;

import static com.google.common.collect.FluentIterable.from;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.blocks.BlockPartitioning;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.defaults.AbstractCPAFactory;
import org.sosy_lab.cpachecker.core.defaults.MergeSepOperator;
import org.sosy_lab.cpachecker.core.defaults.SimplePrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.CPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysisWithBAM;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.Reducer;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.WrapperCPA;
import org.sosy_lab.cpachecker.core.interfaces.pcc.ProofChecker;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractionManager;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;

public class CompositeCPA implements StatisticsProvider, WrapperCPA, ConfigurableProgramAnalysisWithBAM, ProofChecker {

  @Options(prefix="cpa.composite")
  private static class CompositeOptions {
    @Option(secure=true, toUppercase=true, values={"PLAIN", "AGREE"},
        description="which composite merge operator to use (plain or agree)\n"
          + "Both delegate to the component cpas, but agree only allows "
          + "merging if all cpas agree on this. This is probably what you want.")
    private String merge = "AGREE";

    @Option(secure=true,
    description="inform Composite CPA if it is run in a CPA enabled analysis because then it must "
      + "behave differently during merge.")
    private boolean inCPAEnabledAnalysis = false;

    @Option(
      secure = true,
      description =
          "By enabling this option the CompositeTransferRelation"
              + " will compute abstract successors for as many edges as possible in one call. For"
              + " any chain of edges in the CFA which does not have more than one outgoing or leaving"
              + " edge the components of the CompositeCPA are called for each of the edges in this"
              + " chain. Strengthening is still computed after every edge."
              + " The main difference is that while this option is enabled not every ARGState may"
              + " have a single edge connecting to the child/parent ARGState but it may instead"
              + " be a list."
    )
    private boolean aggregateBasicBlocks = false;
  }

  private static class CompositeCPAFactory extends AbstractCPAFactory {

    private CFA cfa = null;
    private ImmutableList<ConfigurableProgramAnalysis> cpas = null;

    @Override
    public ConfigurableProgramAnalysis createInstance() throws InvalidConfigurationException {
      Preconditions.checkState(cpas != null, "CompositeCPA needs wrapped CPAs!");
      Preconditions.checkState(cfa != null, "CompositeCPA needs CFA information!");

      CompositeOptions options = new CompositeOptions();
      getConfiguration().inject(options);

      boolean mergeSep =
          !from(cpas)
              .filter(cpa -> cpa.getMergeOperator() != MergeSepOperator.getInstance())
              .isEmpty();
      if (!mergeSep && options.inCPAEnabledAnalysis && !options.merge.equals("AGREE")) {
        throw new InvalidConfigurationException(
            "Merge PLAIN is currently not supported in predicated analysis");
      }

      return new CompositeCPA(cfa, cpas, options);
    }

    @Override
    public CPAFactory setChild(ConfigurableProgramAnalysis pChild)
        throws UnsupportedOperationException {
      throw new UnsupportedOperationException("Use CompositeCPA to wrap several CPAs!");
    }

    @Override
    public CPAFactory setChildren(List<ConfigurableProgramAnalysis> pChildren) {
      Preconditions.checkNotNull(pChildren);
      Preconditions.checkArgument(!pChildren.isEmpty());
      Preconditions.checkState(cpas == null);

      cpas = ImmutableList.copyOf(pChildren);
      return this;
    }

    @Override
    public <T> CPAFactory set(T pObject, Class<T> pClass) throws UnsupportedOperationException {
      if (pClass.equals(CFA.class)) {
        cfa = (CFA)pObject;
      }
      return super.set(pObject, pClass);
    }
  }

  public static CPAFactory factory() {
    return new CompositeCPAFactory();
  }

  private final ImmutableList<ConfigurableProgramAnalysis> cpas;
  private final CFA cfa;
  private final CompositeOptions options;

  private CompositeCPA(
      CFA pCfa,
      ImmutableList<ConfigurableProgramAnalysis> cpas,
      CompositeOptions pOptions) {
    this.cfa = pCfa;
    this.cpas = cpas;
    this.options = pOptions;
  }

  @Override
  public AbstractDomain getAbstractDomain() {
    ImmutableList.Builder<AbstractDomain> domains = ImmutableList.builder();
    for (ConfigurableProgramAnalysis cpa : cpas) {
      domains.add(cpa.getAbstractDomain());
    }
    return new CompositeDomain(domains.build());
  }

  @Override
  public CompositeTransferRelation getTransferRelation() {
    ImmutableList.Builder<TransferRelation> transferRelations = ImmutableList.builder();
    for (ConfigurableProgramAnalysis cpa : cpas) {
      transferRelations.add(cpa.getTransferRelation());
    }
    return new CompositeTransferRelation(
        transferRelations.build(), cfa, options.aggregateBasicBlocks);
  }

  @Override
  public MergeOperator getMergeOperator() {
    ImmutableList.Builder<MergeOperator> mergeOperators = ImmutableList.builder();
    boolean mergeSep = true;
    for (ConfigurableProgramAnalysis sp : cpas) {
      MergeOperator merge = sp.getMergeOperator();
      if (merge != MergeSepOperator.getInstance()) {
        mergeSep = false;
      }
      mergeOperators.add(merge);
    }

    if (mergeSep) {
      return MergeSepOperator.getInstance();
    } else {
      if (options.inCPAEnabledAnalysis) {
        if (options.merge.equals("AGREE")) {
          Optional<PredicateCPA> predicateCPA = from(cpas).filter(PredicateCPA.class).first();
          Preconditions.checkState(
              predicateCPA.isPresent(), "Option 'inCPAEnabledAnalysis' needs PredicateCPA");
          PredicateAbstractionManager abmgr = predicateCPA.get().getPredicateManager();
          return new CompositeMergeAgreeCPAEnabledAnalysisOperator(
              mergeOperators.build(), getStopOperator().getStopOperators(), abmgr);
        } else {
          throw new AssertionError("Merge PLAIN is currently not supported in predicated analysis");
        }
      } else {
        if (options.merge.equals("AGREE")) {
          return new CompositeMergeAgreeOperator(
              mergeOperators.build(), getStopOperator().getStopOperators());
        } else if (options.merge.equals("PLAIN")) {
          return new CompositeMergePlainOperator(mergeOperators.build());
        } else {
          throw new AssertionError();
        }
      }
    }
  }

  @Override
  public CompositeStopOperator getStopOperator() {
    ImmutableList.Builder<StopOperator> stopOps = ImmutableList.builder();
    for (ConfigurableProgramAnalysis cpa : cpas) {
      stopOps.add(cpa.getStopOperator());
    }
    return new CompositeStopOperator(stopOps.build());
  }

  @Override
  public PrecisionAdjustment getPrecisionAdjustment() {
    ImmutableList.Builder<PrecisionAdjustment> precisionAdjustments = ImmutableList.builder();
    ImmutableList.Builder<SimplePrecisionAdjustment> simplePrecisionAdjustments =
        ImmutableList.builder();
    boolean simplePrec = true;
    for (ConfigurableProgramAnalysis sp : cpas) {
      PrecisionAdjustment prec = sp.getPrecisionAdjustment();
      if (prec instanceof SimplePrecisionAdjustment) {
        simplePrecisionAdjustments.add((SimplePrecisionAdjustment) prec);
      } else {
        simplePrec = false;
      }
      precisionAdjustments.add(prec);
    }
    if (simplePrec) {
      return new CompositeSimplePrecisionAdjustment(simplePrecisionAdjustments.build());
    } else {
      return new CompositePrecisionAdjustment(precisionAdjustments.build());
    }
  }

  @Override
  public Reducer getReducer() throws InvalidConfigurationException {
    ImmutableList.Builder<Reducer> wrappedReducers = ImmutableList.builder();
    for (ConfigurableProgramAnalysis cpa : cpas) {
      Preconditions.checkState(
          cpa instanceof ConfigurableProgramAnalysisWithBAM,
          "wrapped CPA does not support BAM: " + cpa.getClass().getCanonicalName());
      wrappedReducers.add(((ConfigurableProgramAnalysisWithBAM) cpa).getReducer());
    }
    return new CompositeReducer(wrappedReducers.build());
  }

  @Override
  public AbstractState getInitialState(CFANode pNode, StateSpacePartition pPartition) throws InterruptedException {
    Preconditions.checkNotNull(pNode);

    ImmutableList.Builder<AbstractState> initialStates = ImmutableList.builder();
    for (ConfigurableProgramAnalysis sp : cpas) {
      initialStates.add(sp.getInitialState(pNode, pPartition));
    }

    return new CompositeState(initialStates.build());
  }

  @Override
  public Precision getInitialPrecision(CFANode pNode, StateSpacePartition partition) throws InterruptedException {
    Preconditions.checkNotNull(pNode);

    ImmutableList.Builder<Precision> initialPrecisions = ImmutableList.builder();
    for (ConfigurableProgramAnalysis sp : cpas) {
      initialPrecisions.add(sp.getInitialPrecision(pNode, partition));
    }
    return new CompositePrecision(initialPrecisions.build());
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    for (ConfigurableProgramAnalysis cpa: cpas) {
      if (cpa instanceof StatisticsProvider) {
        ((StatisticsProvider)cpa).collectStatistics(pStatsCollection);
      }
    }
  }

  @Override
  public <T extends ConfigurableProgramAnalysis> T retrieveWrappedCpa(Class<T> pType) {
    if (pType.isAssignableFrom(getClass())) {
      return pType.cast(this);
    }
    for (ConfigurableProgramAnalysis cpa : cpas) {
      if (pType.isAssignableFrom(cpa.getClass())) {
        return pType.cast(cpa);
      } else if (cpa instanceof WrapperCPA) {
        T result = ((WrapperCPA)cpa).retrieveWrappedCpa(pType);
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }

  @Override
  public ImmutableList<ConfigurableProgramAnalysis> getWrappedCPAs() {
    return cpas;
  }

  @Override
  public boolean areAbstractSuccessors(AbstractState pElement, CFAEdge pCfaEdge, Collection<? extends AbstractState> pSuccessors) throws CPATransferException, InterruptedException {
    return getTransferRelation().areAbstractSuccessors(pElement, pCfaEdge, pSuccessors, cpas);
  }

  @Override
  public boolean isCoveredBy(AbstractState pElement, AbstractState pOtherElement) throws CPAException, InterruptedException {
    return getStopOperator().isCoveredBy(pElement, pOtherElement, cpas);
  }

  @Override
  public void setPartitioning(BlockPartitioning partitioning) {
    cpas.forEach(
        cpa -> {
          Preconditions.checkState(
              cpa instanceof ConfigurableProgramAnalysisWithBAM,
              "wrapped CPA does not support BAM: " + cpa.getClass().getCanonicalName());
          ((ConfigurableProgramAnalysisWithBAM) cpa).setPartitioning(partitioning);
        });
  }

  @Override
  public boolean isCoveredByRecursiveState(AbstractState pState1, AbstractState pState2)
      throws CPAException, InterruptedException {
    CompositeState state1 = (CompositeState) pState1;
    CompositeState state2 = (CompositeState) pState2;

    List<AbstractState> states1 = state1.getWrappedStates();
    List<AbstractState> states2 = state2.getWrappedStates();

    if (states1.size() != cpas.size()) {
      return false;
    }

    for (int idx = 0; idx < states1.size(); idx++) {
      if (!((ConfigurableProgramAnalysisWithBAM) cpas.get(idx))
          .isCoveredByRecursiveState(states1.get(idx), states2.get(idx))) {
        return false;
      }
    }

    return true;
  }
}
