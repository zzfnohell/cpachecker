/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
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
package org.sosy_lab.cpachecker.util;

import static org.sosy_lab.common.collect.Collections3.transformedImmutableSetCopy;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LogManagerWithoutDuplicates;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.ASimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CArraySubscriptExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression.BinaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpressionBuilder;
import org.sosy_lab.cpachecker.cfa.ast.c.CCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CComplexCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CComplexTypeDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CDesignatedInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerList;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CIntegerLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CPointerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclarationVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CStatementVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeDefDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression.UnaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.DefaultCExpressionVisitor;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.c.CAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.cfa.simplification.ExpressionSimplificationVisitor;
import org.sosy_lab.cpachecker.cfa.types.c.CEnumType.CEnumerator;
import org.sosy_lab.cpachecker.cfa.types.c.CNumericTypes;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cpa.assumptions.genericassumptions.GenericAssumptionBuilder;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;

/**
 * Generate assumptions related to over/underflow
 * of arithmetic operations
 */
@Options(prefix="overflow")
public final class ArithmeticOverflowAssumptionBuilder implements
                                                 GenericAssumptionBuilder {

  @Option(description = "Only check live variables for overflow,"
      + " as compiler can remove dead variables.", secure=true)
  private boolean useLiveness = true;

  @Option(description = "Track overflows in left-shift operations.")
  private boolean trackLeftShifts = true;

  @Option(description = "Track overflows in additive(+/-) operations.")
  private boolean trackAdditiveOperations = true;

  @Option(description = "Track overflows in multiplication operations.")
  private boolean trackMultiplications = true;

  @Option(description = "Track overflows in division(/ or %) operations.")
  private boolean trackDivisions = true;

  @Option(description = "Track overflows in binary expressions involving pointers.")
  private boolean trackPointers = false;

  @Option(description = "Simplify overflow assumptions.")
  private boolean simplifyExpressions = true;

  private final Map<CType, CLiteralExpression> upperBounds;
  private final Map<CType, CLiteralExpression> lowerBounds;
  private final Map<CType, CLiteralExpression> width;
  private final CBinaryExpressionBuilder cBinaryExpressionBuilder;
  private final ExpressionSimplificationVisitor simplificationVisitor;
  private final CFA cfa;
  private final LogManager logger;

  public ArithmeticOverflowAssumptionBuilder(
      CFA cfa,
      LogManager logger,
      Configuration pConfiguration) throws InvalidConfigurationException {
    pConfiguration.inject(this);
    this.logger = logger;
    this.cfa = cfa;
    if (useLiveness) {
      Preconditions.checkState(cfa.getLiveVariables().isPresent(),
          "Liveness information is required for overflow analysis.");
    }

    upperBounds = new HashMap<>();
    lowerBounds = new HashMap<>();
    width = new HashMap<>();

    // TODO: find out if the bare types even occur, or if they are always converted to the SIGNED
    // variants. In that case we could remove the lines with types without the SIGNED_ prefix
    // (though this should really make no difference in performance).
    trackType(CNumericTypes.INT);
    trackType(CNumericTypes.SIGNED_INT);
    trackType(CNumericTypes.LONG_INT);
    trackType(CNumericTypes.SIGNED_LONG_INT);
    trackType(CNumericTypes.LONG_LONG_INT);
    trackType(CNumericTypes.SIGNED_LONG_LONG_INT);

    cBinaryExpressionBuilder = new CBinaryExpressionBuilder(
        cfa.getMachineModel(),
        logger);
    simplificationVisitor =
        new ExpressionSimplificationVisitor(
            cfa.getMachineModel(), new LogManagerWithoutDuplicates(logger));
  }

  /**
   * Returns assumptions required for proving that none of the expressions contained in {@code
   * pEdge} result in overflows.
   *
   * @param pEdge Input CFA edge.
   */
  @Override
  public Set<CExpression> assumptionsForEdge(CFAEdge pEdge) throws UnrecognizedCodeException {
    Set<CExpression> result = new LinkedHashSet<>();

    // Node is used for liveness calculation, and predecessor will contain
    // the live variables of the successor.
    CFANode node = pEdge.getPredecessor();
    AssumptionsFinder finder = new AssumptionsFinder(result, node);


    switch (pEdge.getEdgeType()) {
      case BlankEdge:

        // Can't be an overflow if we don't do anything.
        break;
      case AssumeEdge:
        CAssumeEdge assumeEdge = (CAssumeEdge) pEdge;
        assumeEdge.getExpression().accept(finder);
        break;
      case FunctionCallEdge:
        CFunctionCallEdge fcallEdge = (CFunctionCallEdge) pEdge;

        // Overflows in argument parameters.
        for (CExpression e : fcallEdge.getArguments()) {
          e.accept(finder);
        }
        break;
      case StatementEdge:
        CStatementEdge stmtEdge = (CStatementEdge) pEdge;
        stmtEdge.getStatement().accept(finder);
        break;
      case DeclarationEdge:
        CDeclarationEdge declarationEdge = (CDeclarationEdge) pEdge;
        declarationEdge.getDeclaration().accept(finder);
        break;
      case ReturnStatementEdge:
        CReturnStatementEdge returnEdge = (CReturnStatementEdge) pEdge;
        if (returnEdge.getExpression().isPresent()) {
          returnEdge.getExpression().get().accept(finder);
        }
        break;
      case FunctionReturnEdge:
      case CallToReturnEdge:

        // No overflows for summary edges.
        break;
      default:
        throw new UnsupportedOperationException("Unexpected edge type");
    }

    if (simplifyExpressions) {
      return transformedImmutableSetCopy(result, x -> x.accept(simplificationVisitor));
    }
    return result;
  }

  private void trackType(CSimpleType type) {
    CIntegerLiteralExpression typeMinValue =
        new CIntegerLiteralExpression(
            FileLocation.DUMMY, type, cfa.getMachineModel().getMinimalIntegerValue(type));
    CIntegerLiteralExpression typeMaxValue =
        new CIntegerLiteralExpression(
            FileLocation.DUMMY, type, cfa.getMachineModel().getMaximalIntegerValue(type));
    CIntegerLiteralExpression typeWidth =
        new CIntegerLiteralExpression(
            FileLocation.DUMMY,
            type,
            getWidthForMaxOf(cfa.getMachineModel().getMaximalIntegerValue(type)));

    upperBounds.put(type, typeMaxValue);
    lowerBounds.put(type, typeMinValue);
    width.put(type, typeWidth);
  }

  /**
   * Compute assumptions whose conjunction states that the expression does not overflow the allowed
   * bound of its type.
   */
  private void addAssumptionOnBounds(CExpression exp, Set<CExpression> result, CFANode node)
      throws UnrecognizedCodeException {
    if (useLiveness) {
      Set<CSimpleDeclaration> referencedDeclarations =
          CFAUtils.getIdExpressionsOfExpression(exp)
              .transform(CIdExpression::getDeclaration)
              .toSet();

      Set<ASimpleDeclaration> liveVars = cfa.getLiveVariables().get().getLiveVariablesForNode(node);
      if (Sets.intersection(referencedDeclarations, liveVars).isEmpty()) {
        logger.log(Level.FINE, "No live variables found in expression", exp,
            "skipping");
        return;
      }
    }

    if (isBinaryExpressionThatMayOverflow(exp)) {
      CBinaryExpression binexp = (CBinaryExpression) exp;
      BinaryOperator binop = binexp.getOperator();
      CType calculationType = binexp.getCalculationType();
      CExpression op1 = binexp.getOperand1();
      CExpression op2 = binexp.getOperand2();
      if (trackAdditiveOperations
          && (binop.equals(BinaryOperator.PLUS) || binop.equals(BinaryOperator.MINUS))) {
        if (lowerBounds.get(calculationType) != null) {
          result.add(getLowerAssumption(op1, op2, binop, lowerBounds.get(calculationType)));
        }
        if (upperBounds.get(calculationType) != null) {
          result.add(getUpperAssumption(op1, op2, binop, upperBounds.get(calculationType)));
        }
      } else if (trackMultiplications && binop.equals(BinaryOperator.MULTIPLY)) {
        if (lowerBounds.get(calculationType) != null && upperBounds.get(calculationType) != null) {
          addMultiplicationAssumptions(
              op1, op2, lowerBounds.get(calculationType), upperBounds.get(calculationType), result);
        }
      } else if (trackDivisions
          && (binop.equals(BinaryOperator.DIVIDE) || binop.equals(BinaryOperator.MODULO))) {
        if (lowerBounds.get(calculationType) != null) {
          addDivisionAssumption(op1, op2, lowerBounds.get(calculationType), result);
        }
      } else if (trackLeftShifts && binop.equals(BinaryOperator.SHIFT_LEFT)) {
        if (upperBounds.get(calculationType) != null && width.get(calculationType) != null) {
          addLeftShiftAssumptions(op1, op2, upperBounds.get(calculationType), result);
        }
      }
    } else if (exp instanceof CUnaryExpression) {
      CType calculationType = ((CUnaryExpression) exp).getExpressionType();
      if (lowerBounds.get(calculationType) != null) {
        CUnaryExpression unaryexp = (CUnaryExpression) exp;
        CExpression operand = unaryexp.getOperand();
        result.add(
            cBinaryExpressionBuilder.buildBinaryExpression(
                operand, lowerBounds.get(calculationType), BinaryOperator.NOT_EQUALS));
      }
    } else {
      // TODO: check out and implement in case this happens
    }

  }

  private boolean isBinaryExpressionThatMayOverflow(CExpression pExp) {
    if (pExp instanceof CBinaryExpression) {
      CBinaryExpression binexp = (CBinaryExpression) pExp;
      CExpression op1 = binexp.getOperand1();
      CExpression op2 = binexp.getOperand2();
      if (op1.getExpressionType() instanceof CPointerType
          || op2.getExpressionType() instanceof CPointerType) {
        // There are no classical arithmetic overflows in binary operations involving pointers,
        // since pointer types are not necessarily signed integer types as far as ISO/IEC 9899:2018
        // (C17) is concerned. So we do not track this by default, but make it configurable:
        return trackPointers;
      } else {
        return true;
      }
    } else {
      return false;
    }
  }

  /**
   * see {@link ArithmeticOverflowAssumptionBuilder#getAdditiveAssumption(CExpression, CExpression, BinaryOperator, CLiteralExpression, boolean)}
   */
  private CExpression getUpperAssumption(CExpression operand1, CExpression operand2, BinaryOperator operator,
      CLiteralExpression max) throws UnrecognizedCodeException {
    return getAdditiveAssumption(operand1, operand2, operator, max, true);
  }

  /**
   * see {@link ArithmeticOverflowAssumptionBuilder#getAdditiveAssumption(CExpression, CExpression, BinaryOperator, CLiteralExpression, boolean)}
   */
  private CExpression getLowerAssumption(CExpression operand1, CExpression operand2, BinaryOperator operator,
      CLiteralExpression min) throws UnrecognizedCodeException {
    return getAdditiveAssumption(operand1, operand2, operator, min, false);
  }

  /**
   * This helper method generates assumptions for checking overflows
   * in signed integer additions/subtractions. Since the assumptions
   * are {@link CExpression}s as well, they are structured in such a
   * way that they do not suffer* from overflows themselves (this is of
   * particular importance e.g. if bit vector theory is used for
   * representation!)
   * *The assumptions contain overflows because the second part
   * is always evaluated, but their resulting value will then
   * not depend on the outcome of that part of the formula!
   *
   * For addition (operator = BinaryOperator.PLUS) these assumptions
   * are lower and upper limits:
   * (operand2 <= 0) | (operand1 <= limit - operand2) // upper limit
   * (operand2 >= 0) | (operand1 >= limit - operand2) // lower limit
   *
   * For subtraction (operator = BinaryOperator.MINUS) the assumptions
   * are lower and upper limits:
   * (operand2 >= 0) | (operand1 <= limit + operand2) // upper limit
   * (operand2 <= 0) | (operand1 >= limit + operand2) // lower limit
   *
   * @param operand1 first operand in the C Expression for which the
   *        assumption should be generated
   * @param operand2 second operand in the C Expression for which the
   *        assumption should be generated
   * @param operator either BinaryOperator.MINUS or BinaryOperator.PLUS
   * @param limit the {@link CLiteralExpression} representing the
   *        overflow bound for the type of the expression
   * @param isUpperLimit whether the limit supplied is the upper bound
   *        (otherwise it will be used as lower bound)
   * @return an assumption that has to hold in order for the input
   *         addition/subtraction NOT to have an overflow
   */
  private CExpression getAdditiveAssumption(CExpression operand1, CExpression operand2,
      BinaryOperator operator, CLiteralExpression limit, boolean isUpperLimit)
      throws UnrecognizedCodeException {

    boolean isMinusMode = (operator == BinaryOperator.MINUS);
    assert (isMinusMode
        || (operator == BinaryOperator.PLUS)) : "operator has to be either BinaryOperator.PLUS or BinaryOperator.MINUS!";

    // We construct assumption by writing each of the 4 possible assumptions as:
    // term1 | term3

    // where term1 is structured this way:
    // operand2 term1Operator 0
    BinaryOperator term1Operator =
        (isUpperLimit ^ isMinusMode) ? BinaryOperator.LESS_EQUAL : BinaryOperator.GREATER_EQUAL;
    CExpression term1 = cBinaryExpressionBuilder.buildBinaryExpression(operand2,
        CIntegerLiteralExpression.ZERO, term1Operator);

    // and term2 is structured this way:
    // limit term2Operator operand2
    BinaryOperator term2Operator = isMinusMode ? BinaryOperator.PLUS : BinaryOperator.MINUS;
    CExpression term2 =
        cBinaryExpressionBuilder.buildBinaryExpression(limit, operand2, term2Operator);

    // and term3 is structured this way:
    // operand1 term3Operator term2
    BinaryOperator term3Operator =
        isUpperLimit ? BinaryOperator.LESS_EQUAL : BinaryOperator.GREATER_EQUAL;
    CExpression term3 =
        cBinaryExpressionBuilder.buildBinaryExpression(operand1, term2, term3Operator);

    // the final assumption will look like this:
    // (operand1 term1Operator 0) | ( operand1 term3Operator (limit term2Operator operand2) )
    CExpression assumption =
        cBinaryExpressionBuilder.buildBinaryExpression(term1, term3, BinaryOperator.BINARY_OR);

    return assumption;
  }

  /**
   * This helper method generates assumptions for checking overflows
   * in signed integer multiplications. Since the assumptions
   * are {@link CExpression}s as well, they are structured in such a
   * way that they do not suffer* from overflows themselves (this is of
   * particular importance e.g. if bit vector theory is used for
   * representation!)
   * *The assumptions contain overflows because the second part
   * is always evaluated, but their resulting value will then
   * not depend on the outcome of that part of the formula!
   *
   * The necessary assumptions for multiplication to be free from
   * overflows look as follows:
   * (operand2 <= 0) | (operand1 <= pUpperLimit / operand2)
   * (operand2 <= 0) | (operand1 >= pLowerLimit / operand2)
   * (operand1 <= 0) | (operand2 >= pLowerLimit / operand1)
   * (operand1 >= 0) | (operand2 >= pUpperLimit / operand1)
   *
   * @param operand1 first operand in the C Expression for which the
   *        assumption should be generated
   * @param operand2 second operand in the C Expression for which the
   *        assumption should be generated
   *
   * @param pLowerLimit the {@link CLiteralExpression} representing the
   *        overflow bound for the type of the expression
   * @param pUpperLimit the {@link CLiteralExpression} representing the
   *        overflow bound for the type of the expression
   * @param result the set to which the generated assumptions are added
   */
  private void addMultiplicationAssumptions(CExpression operand1, CExpression operand2,
      CLiteralExpression pLowerLimit, CLiteralExpression pUpperLimit, Set<CExpression> result)
      throws UnrecognizedCodeException {

    for (boolean operand1isFirstOperand : new boolean[] { false, true }) {
      CExpression firstOperand = operand1isFirstOperand ? operand1 : operand2;
      CExpression secondOperand = operand1isFirstOperand ? operand2 : operand1;
      for (boolean usesUpperLimit : new boolean[] { false, true }) {
        CLiteralExpression limit = usesUpperLimit ? pUpperLimit : pLowerLimit;

        // We construct assumption by writing each of the 4 possible assumptions as:
        // term1 | term3

        // where term1 is structured this way:
        // firstOperand term1Operator 0
        BinaryOperator term1Operator = usesUpperLimit && operand1isFirstOperand
            ? BinaryOperator.GREATER_EQUAL : BinaryOperator.LESS_EQUAL;
        CExpression term1 = cBinaryExpressionBuilder.buildBinaryExpression(firstOperand,
            CIntegerLiteralExpression.ZERO, term1Operator);

        // and term2 is structured this way:
        // limit BinaryOperator.DIVIDE firstOperand
        CExpression term2 = cBinaryExpressionBuilder.buildBinaryExpression(limit, firstOperand,
            BinaryOperator.DIVIDE);

        // and term3 is structured this way:
        // secondOperand term3Operator term2
        BinaryOperator term3Operator = usesUpperLimit && !operand1isFirstOperand
            ? BinaryOperator.LESS_EQUAL : BinaryOperator.GREATER_EQUAL;
        CExpression term3 =
            cBinaryExpressionBuilder.buildBinaryExpression(secondOperand, term2, term3Operator);

        // the final assumption will look like this:
        // (firstOperand term1Operator 0) |
        // ( secondOperand term3Operator (limit BinaryOperator.DIVIDE firstOperand) )
        CExpression assumption =
            cBinaryExpressionBuilder.buildBinaryExpression(term1, term3, BinaryOperator.BINARY_OR);
        result.add(assumption);
      }
    }
  }


  /**
   * This helper method generates assumptions for checking overflows
   * in signed integer divisions and modulo operations.
   *
   * The necessary assumption for division or modulo to be free from
   * overflows looks as follows:
   *
   * (operand1 != limit) | (operand2 != -1)
   *
   * @param operand1 first operand in the C Expression for which the
   *        assumption should be generated
   * @param operand2 second operand in the C Expression for which the
   *        assumption should be generated
   *
   * @param limit the smallest value in the expression's type
   * @param result the set to which the generated assumptions are added
   */
  private void addDivisionAssumption(
      CExpression operand1, CExpression operand2, CLiteralExpression limit, Set<CExpression> result)
      throws UnrecognizedCodeException {

    // operand1 != limit
    CExpression term1 =
        cBinaryExpressionBuilder.buildBinaryExpression(operand1, limit, BinaryOperator.NOT_EQUALS);
    // -1
    CExpression term2 = new CUnaryExpression(FileLocation.DUMMY, CNumericTypes.INT,
        CIntegerLiteralExpression.ZERO, UnaryOperator.MINUS);
    // operand2 != -1
    CExpression term3 =
        cBinaryExpressionBuilder.buildBinaryExpression(operand2, term2, BinaryOperator.NOT_EQUALS);
    // (operand1 != INT_MIN) | (operand2 != -1)
    CExpression assumption =
        cBinaryExpressionBuilder.buildBinaryExpression(term1, term3, BinaryOperator.BINARY_OR);
    result.add(assumption);
  }

  /**
   * @param operand1 first operand in the C Expression for which the assumption should be generated
   * @param operand2 second operand in the C Expression for which the assumption should be generated
   * @param limit the largest value in the expression's type
   * @param result the set to which the generated assumptions are added
   */
  private void addLeftShiftAssumptions(
      CExpression operand1,
      CExpression operand2,
      CLiteralExpression limit,
      Set<CExpression> result)
      throws UnrecognizedCodeException {

    // For no undefined behavior, both operands need to be positive:
    // But this is (currently) not considered as overflow!
    /*result.add(cBinaryExpressionBuilder.buildBinaryExpression(operand1,
        CIntegerLiteralExpression.ZERO, BinaryOperator.GREATER_EQUAL));
    result.add(cBinaryExpressionBuilder.buildBinaryExpression(operand2,
        CIntegerLiteralExpression.ZERO, BinaryOperator.GREATER_EQUAL));*/

    // Shifting the precision of the type or a bigger number of bits  is undefined behavior:
    // operand2 < width
    // But this is (currently) not considered as overflow!
    /*result.add(
    cBinaryExpressionBuilder.buildBinaryExpression(operand2, width, BinaryOperator.LESS_THAN));*/

    // Shifting out set bits is undefined behavior that is considered to be an overflow.
    // This is equivalent to the assumption:
    // operand1 <= (limit >> operand2)
    CExpression term1 = cBinaryExpressionBuilder.buildBinaryExpression(limit, operand2, BinaryOperator.SHIFT_RIGHT);
    result.add(
        cBinaryExpressionBuilder.buildBinaryExpression(operand1, term1, BinaryOperator.LESS_EQUAL));
  }

  private static BigInteger getWidthForMaxOf(BigInteger pMax) {
    return BigInteger.valueOf(pMax.bitLength() + 1);
  }

  private class AssumptionsFinder extends DefaultCExpressionVisitor<Void, UnrecognizedCodeException>
      implements CStatementVisitor<Void, UnrecognizedCodeException>,
          CSimpleDeclarationVisitor<Void, UnrecognizedCodeException>,
          CInitializerVisitor<Void, UnrecognizedCodeException> {

    private final Set<CExpression> assumptions;
    private final CFANode node;

    private AssumptionsFinder(Set<CExpression> pAssumptions, CFANode node) {
      assumptions = pAssumptions;
      this.node = node;
    }

    @Override
    public Void visit(CBinaryExpression pIastBinaryExpression) throws UnrecognizedCodeException {
      if (resultCanOverflow(pIastBinaryExpression)) {
        addAssumptionOnBounds(pIastBinaryExpression, assumptions, node);
      }
      pIastBinaryExpression.getOperand1().accept(this);
      pIastBinaryExpression.getOperand2().accept(this);
      return null;
    }

    @Override
    protected Void visitDefault(CExpression exp) throws UnrecognizedCodeException {
      return null;
    }

    @Override
    public Void visit(CArraySubscriptExpression pIastArraySubscriptExpression)
        throws UnrecognizedCodeException {
      return pIastArraySubscriptExpression.getSubscriptExpression().accept(this);
    }

    @Override
    public Void visit(CPointerExpression pointerExpression) throws UnrecognizedCodeException {
      return pointerExpression.getOperand().accept(this);
    }

    @Override
    public Void visit(CComplexCastExpression complexCastExpression)
        throws UnrecognizedCodeException {
      return complexCastExpression.getOperand().accept(this);
    }

    @Override
    public Void visit(CUnaryExpression pIastUnaryExpression) throws UnrecognizedCodeException {
      if (resultCanOverflow(pIastUnaryExpression)) {
        addAssumptionOnBounds(pIastUnaryExpression, assumptions, node);
      }
      return pIastUnaryExpression.getOperand().accept(this);
    }

    @Override
    public Void visit(CCastExpression pIastCastExpression) throws UnrecognizedCodeException {
      // TODO: can cast itself cause overflows?
      return pIastCastExpression.getOperand().accept(this);
    }

    @Override
    public Void visit(CExpressionStatement pIastExpressionStatement)
        throws UnrecognizedCodeException {
      return pIastExpressionStatement.getExpression().accept(this);
    }

    @Override
    public Void visit(CExpressionAssignmentStatement pIastExpressionAssignmentStatement)
        throws UnrecognizedCodeException {
      return pIastExpressionAssignmentStatement.getRightHandSide().accept(this);
    }

    @Override
    public Void visit(CFunctionCallAssignmentStatement pIastFunctionCallAssignmentStatement)
        throws UnrecognizedCodeException {
      for (CExpression arg : pIastFunctionCallAssignmentStatement
          .getRightHandSide().getParameterExpressions()) {
        arg.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(CFunctionCallStatement pIastFunctionCallStatement)
        throws UnrecognizedCodeException {
      for (CExpression arg : pIastFunctionCallStatement
          .getFunctionCallExpression()
          .getParameterExpressions()) {
        arg.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(CFunctionDeclaration pDecl) throws UnrecognizedCodeException {
      // no overflows in CFunctionDeclaration
      return null;
    }

    @Override
    public Void visit(CComplexTypeDeclaration pDecl) throws UnrecognizedCodeException {
      // no overflows in CComplexTypeDeclaration
      return null;
    }

    @Override
    public Void visit(CTypeDefDeclaration pDecl) throws UnrecognizedCodeException {
      // no overflows in CTypeDefDeclaration
      return null;
    }

    @Override
    public Void visit(CVariableDeclaration pDecl) throws UnrecognizedCodeException {
      // rhs of CVariableDeclaration can contain overflows!
      if (pDecl.getInitializer() != null) {
        pDecl.getInitializer().accept(this);
      }
      return null;
    }

    @Override
    public Void visit(CParameterDeclaration pDecl) throws UnrecognizedCodeException {
      // no overflows in CParameterDeclaration
      return null;
    }

    @Override
    public Void visit(CEnumerator pDecl) throws UnrecognizedCodeException {
      // no overflows in CEnumerator
      return null;
    }

    @Override
    public Void visit(CInitializerExpression pInitializerExpression)
        throws UnrecognizedCodeException {
      // CInitializerExpression has a CExpression that can contain an overflow:
      pInitializerExpression.getExpression().accept(this);
      return null;
    }

    @Override
    public Void visit(CInitializerList pInitializerList) throws UnrecognizedCodeException {
      // check each CInitializer for overflow:
      for (CInitializer initializer : pInitializerList.getInitializers()) {
        initializer.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(CDesignatedInitializer pCStructInitializerPart)
        throws UnrecognizedCodeException {
      // CDesignatedInitializer has a CInitializer on the rhs that can contain an overflow:
      pCStructInitializerPart.getRightHandSide().accept(this);
      return null;
    }
  }

  /**
   * Whether the given operator can create new expression.
   */
  private boolean resultCanOverflow(CExpression expr) {
    if (expr instanceof CBinaryExpression) {
      switch (((CBinaryExpression) expr).getOperator()) {
        case MULTIPLY:
        case DIVIDE:
        case PLUS:
        case MINUS:
        case SHIFT_LEFT:
        case SHIFT_RIGHT:
          return true;
        case LESS_THAN:
        case GREATER_THAN:
        case LESS_EQUAL:
        case GREATER_EQUAL:
        case BINARY_AND:
        case BINARY_XOR:
        case BINARY_OR:
        case EQUALS:
        case NOT_EQUALS:
        default:
          return false;
      }
    } else if (expr instanceof CUnaryExpression) {
      switch (((CUnaryExpression) expr).getOperator()) {
        case MINUS:
          return true;
        default:
          return false;
      }
    }
    return false;
  }

}
