/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.optimizations;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.trino.metadata.Metadata;
import io.trino.metadata.MetadataManager;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.IrTypeAnalyzer;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.transaction.TestingTransactionManager;
import io.trino.transaction.TransactionManager;
import io.trino.type.DateTimes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimeWithTimeZoneType.createTimeWithTimeZoneType;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.ir.BooleanLiteral.FALSE_LITERAL;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.NOT_EQUAL;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.ir.LogicalExpression.Operator.OR;
import static io.trino.sql.planner.SymbolsExtractor.extractUnique;
import static io.trino.sql.planner.TestingPlannerContext.plannerContextBuilder;
import static io.trino.testing.TransactionBuilder.transaction;
import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

public class TestExpressionEquivalence
{
    private static final TestingTransactionManager TRANSACTION_MANAGER = new TestingTransactionManager();
    private static final PlannerContext PLANNER_CONTEXT = plannerContextBuilder()
            .withTransactionManager(TRANSACTION_MANAGER)
            .build();
    private static final ExpressionEquivalence EQUIVALENCE = new ExpressionEquivalence(
            PLANNER_CONTEXT.getMetadata(),
            PLANNER_CONTEXT.getFunctionManager(),
            PLANNER_CONTEXT.getTypeManager(),
            new IrTypeAnalyzer(PLANNER_CONTEXT));

    private static final TestingFunctionResolution FUNCTIONS = new TestingFunctionResolution();
    private static final ResolvedFunction MOD = FUNCTIONS.resolveFunction("mod", fromTypes(INTEGER, INTEGER));

    @Test
    public void testEquivalent()
    {
        assertEquivalent(
                new Cast(new NullLiteral(), BIGINT),
                new Cast(new NullLiteral(), BIGINT));
        assertEquivalent(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a_bigint"), new SymbolReference("b_double")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("b_double"), new SymbolReference("a_bigint")));
        assertEquivalent(
                TRUE_LITERAL,
                TRUE_LITERAL);
        assertEquivalent(
                GenericLiteral.constant(INTEGER, 4L),
                GenericLiteral.constant(INTEGER, 4L));
        assertEquivalent(
                GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("4.4"))),
                GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("4.4"))));
        assertEquivalent(
                GenericLiteral.constant(VARCHAR, Slices.utf8Slice("foo")),
                GenericLiteral.constant(VARCHAR, Slices.utf8Slice("foo")));

        assertEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)));
        assertEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("4.4"))), GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("5.5")))),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("5.5"))), GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("4.4")))));
        assertEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(VARCHAR, Slices.utf8Slice("foo")), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("bar"))),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(VARCHAR, Slices.utf8Slice("bar")), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("foo"))));
        assertEquivalent(
                new ComparisonExpression(NOT_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(NOT_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)));
        assertEquivalent(
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)));
        assertEquivalent(
                new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)));
        assertEquivalent(
                new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)));
        assertEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createTimestampType(9), DateTimes.parseTimestamp(9, "2020-05-10 12:34:56.123456789")), GenericLiteral.constant(createTimestampType(9), DateTimes.parseTimestamp(9, "2021-05-10 12:34:56.123456789"))),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createTimestampType(9), DateTimes.parseTimestamp(9, "2021-05-10 12:34:56.123456789")), GenericLiteral.constant(createTimestampType(9), DateTimes.parseTimestamp(9, "2020-05-10 12:34:56.123456789"))));
        assertEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2020-05-10 12:34:56.123456789 +8")), GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2021-05-10 12:34:56.123456789 +8"))),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2021-05-10 12:34:56.123456789 +8")), GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2020-05-10 12:34:56.123456789 +8"))));

        assertEquivalent(
                new FunctionCall(MOD.toQualifiedName(), ImmutableList.of(GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L))),
                new FunctionCall(MOD.toQualifiedName(), ImmutableList.of(GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L))));

        assertEquivalent(
                new SymbolReference("a_bigint"),
                new SymbolReference("a_bigint"));
        assertEquivalent(
                new ComparisonExpression(EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")),
                new ComparisonExpression(EQUAL, new SymbolReference("b_bigint"), new SymbolReference("a_bigint")));
        assertEquivalent(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("b_bigint"), new SymbolReference("a_bigint")));

        assertEquivalent(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a_bigint"), new SymbolReference("b_double")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("b_double"), new SymbolReference("a_bigint")));

        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(TRUE_LITERAL, FALSE_LITERAL)),
                new LogicalExpression(AND, ImmutableList.of(FALSE_LITERAL, TRUE_LITERAL)));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)))));
        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)))));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")), new ComparisonExpression(LESS_THAN, new SymbolReference("c_bigint"), new SymbolReference("d_bigint")))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("d_bigint"), new SymbolReference("c_bigint")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("b_bigint"), new SymbolReference("a_bigint")))));
        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")), new ComparisonExpression(LESS_THAN, new SymbolReference("c_bigint"), new SymbolReference("d_bigint")))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("d_bigint"), new SymbolReference("c_bigint")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("b_bigint"), new SymbolReference("a_bigint")))));

        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)))),
                new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)))));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 2L), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 3L), GenericLiteral.constant(INTEGER, 2L)))));

        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)))),
                new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)));
        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)))));
        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 2L), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 3L), GenericLiteral.constant(INTEGER, 2L)))));

        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new SymbolReference("a_boolean"), new SymbolReference("b_boolean"), new SymbolReference("c_boolean"))),
                new LogicalExpression(AND, ImmutableList.of(new SymbolReference("c_boolean"), new SymbolReference("b_boolean"), new SymbolReference("a_boolean"))));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(AND, ImmutableList.of(new SymbolReference("a_boolean"), new SymbolReference("b_boolean"))), new SymbolReference("c_boolean"))),
                new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(AND, ImmutableList.of(new SymbolReference("c_boolean"), new SymbolReference("b_boolean"))), new SymbolReference("a_boolean"))));
        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new SymbolReference("a_boolean"), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("b_boolean"), new SymbolReference("c_boolean"))))),
                new LogicalExpression(AND, ImmutableList.of(new SymbolReference("a_boolean"), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("c_boolean"), new SymbolReference("b_boolean"))), new SymbolReference("a_boolean"))));

        assertEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(OR, ImmutableList.of(new SymbolReference("a_boolean"), new SymbolReference("b_boolean"), new SymbolReference("c_boolean"))), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("d_boolean"), new SymbolReference("e_boolean"))), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("f_boolean"), new SymbolReference("g_boolean"), new SymbolReference("h_boolean"))))),
                new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(OR, ImmutableList.of(new SymbolReference("h_boolean"), new SymbolReference("g_boolean"), new SymbolReference("f_boolean"))), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("b_boolean"), new SymbolReference("a_boolean"), new SymbolReference("c_boolean"))), new LogicalExpression(OR, ImmutableList.of(new SymbolReference("e_boolean"), new SymbolReference("d_boolean"))))));

        assertEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new LogicalExpression(AND, ImmutableList.of(new SymbolReference("a_boolean"), new SymbolReference("b_boolean"), new SymbolReference("c_boolean"))), new LogicalExpression(AND, ImmutableList.of(new SymbolReference("d_boolean"), new SymbolReference("e_boolean"))), new LogicalExpression(AND, ImmutableList.of(new SymbolReference("f_boolean"), new SymbolReference("g_boolean"), new SymbolReference("h_boolean"))))),
                new LogicalExpression(OR, ImmutableList.of(new LogicalExpression(AND, ImmutableList.of(new SymbolReference("h_boolean"), new SymbolReference("g_boolean"), new SymbolReference("f_boolean"))), new LogicalExpression(AND, ImmutableList.of(new SymbolReference("b_boolean"), new SymbolReference("a_boolean"), new SymbolReference("c_boolean"))), new LogicalExpression(AND, ImmutableList.of(new SymbolReference("e_boolean"), new SymbolReference("d_boolean"))))));
    }

    private static void assertEquivalent(Expression leftExpression, Expression rightExpression)
    {
        Set<Symbol> symbols = extractUnique(ImmutableList.of(leftExpression, rightExpression));
        TypeProvider types = TypeProvider.copyOf(symbols.stream()
                .collect(toMap(identity(), TestExpressionEquivalence::generateType)));

        assertThat(areExpressionEquivalent(leftExpression, rightExpression, types))
                .describedAs(format("Expected (%s) and (%s) to be equivalent", leftExpression, rightExpression))
                .isTrue();
        assertThat(areExpressionEquivalent(rightExpression, leftExpression, types))
                .describedAs(format("Expected (%s) and (%s) to be equivalent", rightExpression, leftExpression))
                .isTrue();
    }

    @Test
    public void testNotEquivalent()
    {
        assertNotEquivalent(
                new Cast(new NullLiteral(), BOOLEAN),
                FALSE_LITERAL);
        assertNotEquivalent(
                FALSE_LITERAL,
                new Cast(new NullLiteral(), BOOLEAN));
        assertNotEquivalent(
                TRUE_LITERAL,
                FALSE_LITERAL);
        assertNotEquivalent(
                GenericLiteral.constant(INTEGER, 4L),
                GenericLiteral.constant(INTEGER, 5L));
        assertNotEquivalent(
                GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("4.4"))),
                GenericLiteral.constant(createDecimalType(3, 1), Decimals.valueOfShort(new BigDecimal("5.5"))));
        assertNotEquivalent(
                GenericLiteral.constant(VARCHAR, Slices.utf8Slice("'foo'")),
                GenericLiteral.constant(VARCHAR, Slices.utf8Slice("'bar'")));

        assertNotEquivalent(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)));
        assertNotEquivalent(
                new ComparisonExpression(NOT_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(NOT_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)));
        assertNotEquivalent(
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)));
        assertNotEquivalent(
                new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)));
        assertNotEquivalent(
                new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)),
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)));

        assertNotEquivalent(
                new FunctionCall(MOD.toQualifiedName(), ImmutableList.of(GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L))),
                new FunctionCall(MOD.toQualifiedName(), ImmutableList.of(GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 4L))));

        assertNotEquivalent(
                new SymbolReference("a_bigint"),
                new SymbolReference("b_bigint"));
        assertNotEquivalent(
                new ComparisonExpression(EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")),
                new ComparisonExpression(EQUAL, new SymbolReference("b_bigint"), new SymbolReference("c_bigint")));
        assertNotEquivalent(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("b_bigint"), new SymbolReference("c_bigint")));

        assertNotEquivalent(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a_bigint"), new SymbolReference("b_double")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("b_double"), new SymbolReference("c_bigint")));

        assertNotEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)))));
        assertNotEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 5L)), new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 6L), GenericLiteral.constant(INTEGER, 7L)))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 7L), GenericLiteral.constant(INTEGER, 6L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 5L), GenericLiteral.constant(INTEGER, 6L)))));
        assertNotEquivalent(
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")), new ComparisonExpression(LESS_THAN, new SymbolReference("c_bigint"), new SymbolReference("d_bigint")))),
                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("d_bigint"), new SymbolReference("c_bigint")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("b_bigint"), new SymbolReference("c_bigint")))));
        assertNotEquivalent(
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a_bigint"), new SymbolReference("b_bigint")), new ComparisonExpression(LESS_THAN, new SymbolReference("c_bigint"), new SymbolReference("d_bigint")))),
                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("d_bigint"), new SymbolReference("c_bigint")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("b_bigint"), new SymbolReference("c_bigint")))));

        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(3), DateTimes.parseTimeWithTimeZone(3, "12:34:56.123 +00:00")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(3), DateTimes.parseTimeWithTimeZone(3, "14:34:56.123 +02:00")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(6), DateTimes.parseTimeWithTimeZone(6, "12:34:56.123456 +00:00")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(6), DateTimes.parseTimeWithTimeZone(6, "14:34:56.123456 +02:00")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(9), DateTimes.parseTimeWithTimeZone(9, "12:34:56.123456789 +00:00")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(9), DateTimes.parseTimeWithTimeZone(9, "14:34:56.123456789 +02:00")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(12), DateTimes.parseTimeWithTimeZone(12, "12:34:56.123456789012 +00:00")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimeWithTimeZoneType(12), DateTimes.parseTimeWithTimeZone(12, "14:34:56.123456789012 +02:00")), VARCHAR));

        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(3), DateTimes.parseTimestampWithTimeZone(3, "2020-05-10 12:34:56.123 Europe/Warsaw")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(3), DateTimes.parseTimestampWithTimeZone(3, "2020-05-10 12:34:56.123 Europe/Paris")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(6), DateTimes.parseTimestampWithTimeZone(6, "2020-05-10 12:34:56.123456 Europe/Warsaw")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(6), DateTimes.parseTimestampWithTimeZone(6, "2020-05-10 12:34:56.123456 Europe/Paris")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2020-05-10 12:34:56.123456789 Europe/Warsaw")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(9), DateTimes.parseTimestampWithTimeZone(9, "2020-05-10 12:34:56.123456789 Europe/Paris")), VARCHAR));
        assertNotEquivalent(
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(12), DateTimes.parseTimestampWithTimeZone(12, "2020-05-10 12:34:56.123456789012 Europe/Warsaw")), VARCHAR),
                new Cast(GenericLiteral.constant(createTimestampWithTimeZoneType(12), DateTimes.parseTimestampWithTimeZone(12, "2020-05-10 12:34:56.123456789012 Europe/Paris")), VARCHAR));
    }

    private static void assertNotEquivalent(Expression leftExpression, Expression rightExpression)
    {
        Set<Symbol> symbols = extractUnique(ImmutableList.of(leftExpression, rightExpression));
        TypeProvider types = TypeProvider.copyOf(symbols.stream()
                .collect(toMap(identity(), TestExpressionEquivalence::generateType)));

        assertThat(areExpressionEquivalent(leftExpression, rightExpression, types))
                .describedAs(format("Expected (%s) and (%s) to not be equivalent", leftExpression, rightExpression))
                .isFalse();
        assertThat(areExpressionEquivalent(rightExpression, leftExpression, types))
                .describedAs(format("Expected (%s) and (%s) to not be equivalent", rightExpression, leftExpression))
                .isFalse();
    }

    private static boolean areExpressionEquivalent(Expression leftExpression, Expression rightExpression, TypeProvider types)
    {
        TransactionManager transactionManager = new TestingTransactionManager();
        Metadata metadata = MetadataManager.testMetadataManagerBuilder().withTransactionManager(transactionManager).build();
        return transaction(transactionManager, metadata, new AllowAllAccessControl())
                .singleStatement()
                .execute(TEST_SESSION, transactionSession -> {
                    return EQUIVALENCE.areExpressionsEquivalent(transactionSession, leftExpression, rightExpression, types);
                });
    }

    private static Type generateType(Symbol symbol)
    {
        String typeName = Splitter.on('_').limit(2).splitToList(symbol.getName()).get(1);
        return PLANNER_CONTEXT.getTypeManager().getType(new TypeSignature(typeName, ImmutableList.of()));
    }
}
