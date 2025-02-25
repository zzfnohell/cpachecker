/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2018  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.smg;

import java.util.Collection;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.UniqueIdGenerator;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.PathTemplate;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.core.counterexample.AssumptionToEdgeAllocator;
import org.sosy_lab.cpachecker.core.counterexample.CFAPathWithAdditionalInfo;
import org.sosy_lab.cpachecker.core.counterexample.ConcreteStatePath;
import org.sosy_lab.cpachecker.core.defaults.AutomaticCPAFactory;
import org.sosy_lab.cpachecker.core.defaults.DelegateAbstractDomain;
import org.sosy_lab.cpachecker.core.defaults.MergeJoinOperator;
import org.sosy_lab.cpachecker.core.defaults.MergeSepOperator;
import org.sosy_lab.cpachecker.core.defaults.StopNeverOperator;
import org.sosy_lab.cpachecker.core.defaults.StopSepOperator;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.CPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysisWithAdditionalInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysisWithConcreteCex;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.witnessexport.AdditionalInfoConverter;
import org.sosy_lab.cpachecker.cpa.smg.refiner.SMGPrecision;
import org.sosy_lab.cpachecker.util.predicates.BlockOperator;

@Options(prefix = "cpa.smg")
public class SMGCPA
    implements ConfigurableProgramAnalysis,
        ConfigurableProgramAnalysisWithConcreteCex,
        ConfigurableProgramAnalysisWithAdditionalInfo,
        StatisticsProvider {

  public static CPAFactory factory() {
    return AutomaticCPAFactory.forType(SMGCPA.class);
  }

  @Option(secure=true, name="stop", toUppercase=true, values={"SEP", "NEVER", "END_BLOCK"},
      description="which stop operator to use for the SMGCPA")
  private String stopType = "SEP";

  @Option(secure=true, name="merge", toUppercase=true, values={"SEP", "JOIN"},
      description="which merge operator to use for the SMGCPA")
  private String mergeType = "SEP";

  private final SMGPredicateManager smgPredicateManager;
  private final BlockOperator blockOperator;
  private final MachineModel machineModel;

  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;
  private final Configuration config;
  private final CFA cfa;

  private final AssumptionToEdgeAllocator assumptionToEdgeAllocator;
  private final SMGOptions options;
  private final SMGExportDotOption exportOptions;
  private final SMGStatistics stats = new SMGStatistics();

  // flag whether we perform CEGAR or static analysis.
  private SMGTransferRelationKind kind = SMGTransferRelationKind.STATIC;

  private SMGPrecision precision;


  private SMGCPA(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier,
      CFA pCfa) throws InvalidConfigurationException {
    pConfig.inject(this);

    config = pConfig;
    cfa = pCfa;
    machineModel = cfa.getMachineModel();
    logger = pLogger;
    shutdownNotifier = pShutdownNotifier;

    options = new SMGOptions(config);
    exportOptions = new SMGExportDotOption(options.getExportSMGFilePattern(), options.getExportSMGLevel());

    assumptionToEdgeAllocator = AssumptionToEdgeAllocator.create(config, logger, machineModel);

    blockOperator = new BlockOperator();
    pConfig.inject(blockOperator);
    blockOperator.setCFA(cfa);

    precision = SMGPrecision.createStaticPrecision(options.isHeapAbstractionEnabled());

    smgPredicateManager = new SMGPredicateManager(config, logger, pShutdownNotifier);
  }

  /**
   * Switch analysis to CEGAR instead of static approach.
   *
   * <p>This method should only be called once before starting the analysis.
   */
  public void enableRefinement(PathTemplate pNewPathTemplate) {
    exportOptions.changeToRefinement(pNewPathTemplate);
    kind = SMGTransferRelationKind.REFINEMENT;
    // replace the full precision with an empty, refinable precision
    precision = SMGPrecision.createRefineablePrecision(precision);
  }

  public SMGOptions getOptions() {
    return options;
  }

  @Override
  public AbstractDomain getAbstractDomain() {
    return DelegateAbstractDomain.<UnmodifiableSMGState>getInstance();
  }

  @Override
  public TransferRelation getTransferRelation() {
    return new SMGTransferRelation(
        logger, machineModel, exportOptions, kind, smgPredicateManager, options);
  }

  @Override
  public MergeOperator getMergeOperator() {
    switch (mergeType) {
      case "SEP":
        return MergeSepOperator.getInstance();
      case "JOIN":
        return new MergeJoinOperator(getAbstractDomain());
      default:
        throw new AssertionError("unknown mergetype for SMGCPA");
    }
  }

  @Override
  public StopOperator getStopOperator() {
    switch (stopType) {
      case "END_BLOCK":
        return new SMGStopOperator(getAbstractDomain());
      case "NEVER":
        return StopNeverOperator.getInstance();
      case "SEP":
        return new StopSepOperator(getAbstractDomain());
      default:
        throw new AssertionError("unknown stoptype for SMGCPA");
    }
  }

  @Override
  public PrecisionAdjustment getPrecisionAdjustment() {
    return new SMGPrecisionAdjustment(logger, exportOptions, blockOperator, stats);
  }

  @Override
  public UnmodifiableSMGState getInitialState(CFANode pNode, StateSpacePartition pPartition) {
    SMGState initState = new SMGState(logger, machineModel, options);

    try {
      initState.performConsistencyCheck(SMGRuntimeCheck.FULL);
    } catch (SMGInconsistentException exc) {
      logger.log(Level.SEVERE, exc.getMessage());
    }

    if (pNode instanceof CFunctionEntryNode) {
      CFunctionEntryNode functionNode = (CFunctionEntryNode) pNode;
      try {
        initState.addStackFrame(functionNode.getFunctionDefinition());
        initState.performConsistencyCheck(SMGRuntimeCheck.FULL);
      } catch (SMGInconsistentException exc) {
        logger.log(Level.SEVERE, exc.getMessage());
      }
    }

    return initState;
  }

  @Override
  public Precision getInitialPrecision(CFANode pNode, StateSpacePartition pPartition) {
    return precision;
  }

  @Override
  public ConcreteStatePath createConcreteStatePath(ARGPath pPath) {
    return new SMGConcreteErrorPathAllocator(assumptionToEdgeAllocator).allocateAssignmentsToPath(pPath);
  }

  public LogManager getLogger() {
    return logger;
  }

  public Configuration getConfiguration() {
    return config;
  }

  public CFA getCFA() {
    return cfa;
  }

  public ShutdownNotifier getShutdownNotifier() {
    return shutdownNotifier;
  }

  public SMGPredicateManager getPredicateManager() {
    return smgPredicateManager;
  }

  public BlockOperator getBlockOperator() {
    return blockOperator;
  }

  public void nextRefinement() {
    exportOptions.nextRefinement();
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
  }

  @Override
  public AdditionalInfoConverter exportAdditionalInfoConverter() {
    return new SMGAdditionalInfoConverter();
  }

  @Override
  public CFAPathWithAdditionalInfo createExtendedInfo(ARGPath pPath) {
    return new AdditionalInfoExtractor().createExtendedInfo(pPath);
  }

  private static final UniqueIdGenerator idGenerator = new UniqueIdGenerator();

  /**
   * Get a new ID for a new memory location or region or whatever.
   *
   * <p>We never return ZERO here, because ZERO is used as an ID for NULL.
   */
  public static int getNewValue() {
    return idGenerator.getFreshId() + 1;
  }
}