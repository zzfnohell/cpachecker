/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2017  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.smg.evaluator;

import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManagerWithoutDuplicates;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CRightHandSide;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CArrayType;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cpa.smg.SMGBuiltins;
import org.sosy_lab.cpachecker.cpa.smg.SMGCPA;
import org.sosy_lab.cpachecker.cpa.smg.SMGExportDotOption;
import org.sosy_lab.cpachecker.cpa.smg.SMGInconsistentException;
import org.sosy_lab.cpachecker.cpa.smg.SMGOptions;
import org.sosy_lab.cpachecker.cpa.smg.SMGState;
import org.sosy_lab.cpachecker.cpa.smg.SMGTransferRelationKind;
import org.sosy_lab.cpachecker.cpa.smg.TypeUtils;
import org.sosy_lab.cpachecker.cpa.smg.evaluator.SMGAbstractObjectAndState.SMGAddressValueAndState;
import org.sosy_lab.cpachecker.cpa.smg.evaluator.SMGAbstractObjectAndState.SMGExplicitValueAndState;
import org.sosy_lab.cpachecker.cpa.smg.evaluator.SMGAbstractObjectAndState.SMGValueAndState;
import org.sosy_lab.cpachecker.cpa.smg.graphs.edge.SMGEdgePointsTo;
import org.sosy_lab.cpachecker.cpa.smg.graphs.object.SMGNullObject;
import org.sosy_lab.cpachecker.cpa.smg.graphs.object.SMGObject;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGAddress;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGAddressValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGExplicitValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGKnownAddressValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGKnownExpValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGKnownSymbolicValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGSymbolicValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGUnknownValue;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;

/**
 * The class {@link SMGExpressionEvaluator} is meant to evaluate
 * a expression using an arbitrary SMGState. Thats why it does not
 * permit semantic changes of the state it uses. This class implements
 * additionally the changes that occur while calculating the next smgState
 * in the Transfer Relation. These mainly include changes when evaluating
 * functions. They also contain code that should only be executed during
 * the calculation of the next SMG State, e.g. logging.
 */
public class SMGRightHandSideEvaluator extends SMGExpressionEvaluator {

  private final SMGOptions options;
  private final SMGTransferRelationKind kind;
  public final SMGBuiltins builtins;

  public SMGRightHandSideEvaluator(
      LogManagerWithoutDuplicates pLogger,
      MachineModel pMachineModel,
      SMGOptions pOptions,
      SMGTransferRelationKind pKind,
      SMGExportDotOption exportSMGOptions) {
    super(pLogger, pMachineModel);
    options = pOptions;
    kind = pKind;
    builtins = new SMGBuiltins(this, options, exportSMGOptions, machineModel, logger);
  }

  public SMGExplicitValueAndState forceExplicitValue(
      SMGState smgState, CFAEdge pCfaEdge, CRightHandSide rVal) throws UnrecognizedCodeException {

    ForceExplicitValueVisitor v =
        new ForceExplicitValueVisitor(
            this, smgState, null, machineModel, logger, pCfaEdge, options);

    Value val = rVal.accept(v);

    if (val.isUnknown()) {
      return SMGExplicitValueAndState.of(v.getState(), SMGUnknownValue.INSTANCE);
    }

    return SMGExplicitValueAndState.of(v.getState(),
        SMGKnownExpValue.valueOf(val.asNumericValue().longValue()));
  }

  public SMGState deriveFurtherInformation(SMGState pNewState, boolean pTruthValue, CFAEdge pCfaEdge, CExpression rValue)
      throws CPATransferException {
    AssigningValueVisitor v = new AssigningValueVisitor(this, pNewState, pTruthValue, pCfaEdge);

    rValue.accept(v);
    return v.getAssignedState();
  }

  @Override
  public SMGValueAndState readValue(
      SMGState pSmgState, SMGObject pObject, SMGExplicitValue pOffset, CType pType, CFAEdge pEdge)
      throws SMGInconsistentException, UnrecognizedCodeException {

    if (pOffset.isUnknown() || pObject == null) {
      SMGState errState =
          pSmgState.withInvalidRead().withErrorDescription("Can't evaluate offset or object");
      return SMGValueAndState.withUnknownValue(errState);
    }

    long fieldOffset = pOffset.getAsLong();
    int typeBitSize = getBitSizeof(pEdge, pType, pSmgState);
    int objectBitSize = pObject.getSize();

    //FIXME Does not work with variable array length.
    boolean doesNotFitIntoObject = fieldOffset < 0
        || fieldOffset + typeBitSize > objectBitSize;

    if (doesNotFitIntoObject) {
      SMGState errState = pSmgState.withInvalidRead();
      // Field does not fit size of declared Memory
      logger.log(Level.INFO, pEdge.getFileLocation(), ":", "Field ", "(",
           fieldOffset, ", ", pType.toASTString(""), ")",
          " does not fit object ", pObject, ".");
      final String description;
      if (!pObject.equals(SMGNullObject.INSTANCE)) {
        if (typeBitSize % 8 != 0 || fieldOffset % 8 != 0 || objectBitSize % 8 != 0) {
          description =
              String.format(
                  "Field with %d  bit size can't be read from offset %s bit of object %d bit size",
                  typeBitSize, fieldOffset, objectBitSize);
        } else {
          description =
              String.format(
                  "Field with %d  byte size can't be read from offset %s byte of object %d byte size",
                  typeBitSize / 8, fieldOffset / 8, objectBitSize / 8);
        }
        errState.addInvalidObject(pObject);
      } else {
        description = "NULL pointer dereference on read";
      }
      errState = errState.withErrorDescription(description);
      return SMGValueAndState.withUnknownValue(errState);
    }

    return pSmgState.forceReadValue(pObject, fieldOffset, pType);
  }

  /**
   * Write a value into the SMG.
   *
   * @param pState state with the SMG to be modified.
   * @param pMemoryOfField target-memory where to be written to.
   * @param pFieldOffset offset in pMemoryOfField.
   * @param pRValueType the type of the data to be written (should match into pMemoryOfField minus
   *     pFieldOffset.
   * @param pValue the new value
   * @param pEdge edge for logging
   */
  public SMGState writeValue(
      SMGState pState,
      SMGObject pMemoryOfField,
      long pFieldOffset,
      CType pRValueType,
      SMGSymbolicValue pValue,
      CFAEdge pEdge)
      throws SMGInconsistentException, UnrecognizedCodeException {

    // FIXME Does not work with variable array length.
    // TODO: write value with bit precise size
    int memoryBitSize = pMemoryOfField.getSize();
    int rValueTypeBitSize = getBitSizeof(pEdge, pRValueType, pState);
    boolean doesNotFitIntoObject =
        pFieldOffset < 0 || pFieldOffset + rValueTypeBitSize > memoryBitSize;

    if (doesNotFitIntoObject) {
      // Field does not fit size of declared Memory
      logger.log(
          Level.INFO,
          () ->
              String.format(
                  "%s: Field (%d, %s) does not fit object %s.",
                  pEdge.getFileLocation(),
                  pFieldOffset,
                  pRValueType.toASTString(""),
                  pMemoryOfField));
      SMGState newState = pState.withInvalidWrite();
      if (!pMemoryOfField.equals(SMGNullObject.INSTANCE)) {
        newState = newState.withErrorDescription(String.format(
            "Field with size %d bit can't be written at offset %d bit of object %d bit size",
            rValueTypeBitSize, pFieldOffset, memoryBitSize));
        newState.addInvalidObject(pMemoryOfField);
      } else {
        newState = newState.withErrorDescription("NULL pointer dereference on write");
      }
      return newState;
    }

    if (pValue.isUnknown()) {
      return pState;
    }

    if (pRValueType instanceof CPointerType
        && !(pValue instanceof SMGAddressValue)
        && pValue instanceof SMGKnownSymbolicValue) {
      SMGKnownSymbolicValue knownValue = (SMGKnownSymbolicValue) pValue;
      if (pState.isExplicit(knownValue)) {
        SMGExplicitValue explicit = Preconditions.checkNotNull(pState.getExplicit(knownValue));
        pValue =
            SMGKnownAddressValue.valueOf(
                knownValue, SMGNullObject.INSTANCE, (SMGKnownExpValue) explicit);
      }
    }
    return pState
        .writeValue(
            pMemoryOfField,
            pFieldOffset,
            machineModel.getSizeofInBits(pRValueType).longValueExact(),
            pValue)
        .getState();
  }

  public SMGState assignFieldToState(
      SMGState newState,
      CFAEdge cfaEdge,
      SMGObject memoryOfField,
      long fieldOffset,
      SMGSymbolicValue value,
      CType rValueType)
      throws UnrecognizedCodeException, SMGInconsistentException {

    int sizeOfField = getBitSizeof(cfaEdge, rValueType, newState);

    // FIXME Does not work with variable array length.
    if (memoryOfField.getSize() < sizeOfField) {

      logger.log(
          Level.INFO,
          () ->
              String.format(
                  "%s: Attempting to write %d bytes into a field with size %d bytes: %s",
                  cfaEdge.getFileLocation(),
                  sizeOfField,
                  memoryOfField.getSize(),
                  cfaEdge.getRawStatement()));
    }

    if (isStructOrUnionType(rValueType)) {
      return assignStruct(newState, memoryOfField, fieldOffset, rValueType, value, cfaEdge);
    } else {
      return writeValue(newState, memoryOfField, fieldOffset, rValueType, value, cfaEdge);
    }
  }

  private SMGState assignStruct(
      SMGState pNewState,
      SMGObject pMemoryOfField,
      long pFieldOffset,
      CType pRValueType,
      SMGSymbolicValue pValue,
      CFAEdge pCfaEdge)
      throws SMGInconsistentException, UnrecognizedCodeException {

    if (pValue instanceof SMGKnownAddressValue) {
      SMGKnownAddressValue structAddress = (SMGKnownAddressValue) pValue;

      SMGObject source = structAddress.getObject();
      long structOffset = structAddress.getOffset().getAsLong();

      // FIXME Does not work with variable array length.
      long structSize = structOffset + getBitSizeof(pCfaEdge, pRValueType, pNewState);
      return pNewState.copy(source, pMemoryOfField, structOffset, structSize, pFieldOffset);
    }

    return pNewState;
  }

  public List<SMGAddressValueAndState> handleSafeExternFuction(
      CFunctionCallExpression pFunctionCallExpression, SMGState pSmgState, CFAEdge pCfaEdge)
      throws CPATransferException {
    String calledFunctionName = pFunctionCallExpression.getFunctionNameExpression().toString();
    List<CExpression> parameters = pFunctionCallExpression.getParameterExpressions();
    for (int i = 0; i < parameters.size(); i++) {
      CExpression param = parameters.get(i);
      CType paramType = TypeUtils.getRealExpressionType(param);
      if (paramType instanceof CPointerType || paramType instanceof CArrayType) {
        // assign external value to param
        for (SMGAddressValueAndState addressOfFieldAndState :
            evaluateAddress(pSmgState, pCfaEdge, param)) {
          SMGAddress smgAddress = addressOfFieldAndState.getObject().getAddress();

          // Check that write will be correct
          if (!smgAddress.isUnknown()) {
            SMGObject object = smgAddress.getObject();
            SMGExplicitValue offset = smgAddress.getOffset();
            SMGState smgState = addressOfFieldAndState.getSmgState();
            if (!object.equals(SMGNullObject.INSTANCE)
                && object.getSize() - offset.getAsLong() >= machineModel.getSizeofPtrInBits()
                && (smgState.getHeap().isObjectValid(object)
                    || smgState.getHeap().isObjectExternallyAllocated(object))) {

              SMGEdgePointsTo newParamValue =
                  pSmgState.addExternalAllocation(
                      calledFunctionName + "_Param_No_" + i + "_ID" + SMGCPA.getNewValue());
              pSmgState =
                  assignFieldToState(
                      pSmgState,
                      pCfaEdge,
                      object,
                      offset.getAsLong(),
                      (SMGSymbolicValue) newParamValue.getValue(),
                      paramType);
            }
          }
        }
      }
    }

    CType returnValueType =
        TypeUtils.getRealExpressionType(pFunctionCallExpression.getExpressionType());
    if (returnValueType instanceof CPointerType || returnValueType instanceof CArrayType) {
      return Collections.singletonList(
          SMGAddressValueAndState.of(
              pSmgState,
              pSmgState.addExternalAllocation(calledFunctionName + SMGCPA.getNewValue())));
    }
    return Collections.singletonList(SMGAddressValueAndState.of(pSmgState));
  }

  @Override
  PointerVisitor getPointerVisitor(CFAEdge pCfaEdge, SMGState pNewState) {
    return new RHSPointerAddressVisitor(this, pCfaEdge, pNewState, kind);
  }

  @Override
  ExpressionValueVisitor getExpressionValueVisitor(CFAEdge pCfaEdge, SMGState pNewState) {
    return new RHSExpressionValueVisitor(this, builtins, pCfaEdge, pNewState, kind);
  }

  @Override
  public LValueAssignmentVisitor getLValueAssignmentVisitor(CFAEdge pCfaEdge, SMGState pNewState) {
    return new RHSLValueAssignmentVisitor(this, pCfaEdge, pNewState);
  }

  @Override
  RHSCSizeOfVisitor getSizeOfVisitor(
      CFAEdge pEdge, SMGState pState, Optional<CExpression> pExpression) {
    return new RHSCSizeOfVisitor(this, pEdge, pState, pExpression, kind);
  }

  @Override
  protected SMGValueAndState handleUnknownDereference(SMGState pSmgState, CFAEdge pEdge) {
    return super.handleUnknownDereference(pSmgState.withUnknownDereference(), pEdge);
  }
}
