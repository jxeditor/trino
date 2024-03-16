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
package io.trino.sql.planner.assertions;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.trino.sql.ir.BetweenPredicate;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NotExpression;
import io.trino.sql.ir.SymbolReference;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.NOT_EQUAL;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestExpressionVerifier
{
    @Test
    public void test()
    {
        Expression actual = new NotExpression(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(EQUAL, new SymbolReference("custkey"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN, new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 10L)))));

        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .put("Y", new SymbolReference("custkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);

        assertThat(verifier.process(actual, new NotExpression(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(EQUAL, new SymbolReference("Y"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 10L))))))).isTrue();
        assertThatThrownBy(() -> verifier.process(actual, new NotExpression(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(EQUAL, new SymbolReference("Y"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN, new SymbolReference("Z"), GenericLiteral.constant(INTEGER, 10L)))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing expression for alias Z");
        assertThat(verifier.process(actual, new NotExpression(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(EQUAL, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN, new SymbolReference("X"), GenericLiteral.constant(INTEGER, 10L))))))).isFalse();
    }

    @Test
    public void testCast()
    {
        SymbolAliases aliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(aliases);
        assertThat(verifier.process(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("2")), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("2")))).isTrue();
        assertThat(verifier.process(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("2")), new Cast(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("2")), BIGINT))).isFalse();
        assertThat(verifier.process(new Cast(new SymbolReference("orderkey"), VARCHAR), new Cast(new SymbolReference("X"), VARCHAR))).isTrue();
    }

    @Test
    public void testBetween()
    {
        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .put("Y", new SymbolReference("custkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);
        // Complete match
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)), new BetweenPredicate(new SymbolReference("X"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)))).isTrue();
        // Different value
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)), new BetweenPredicate(new SymbolReference("Y"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)))).isFalse();
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("custkey"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)), new BetweenPredicate(new SymbolReference("X"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)))).isFalse();
        // Different min or max
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 2L), GenericLiteral.constant(INTEGER, 4L)), new BetweenPredicate(new SymbolReference("X"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)))).isFalse();
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)), new BetweenPredicate(new SymbolReference("X"), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("1")), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("2"))))).isFalse();
        assertThat(verifier.process(new BetweenPredicate(new SymbolReference("orderkey"), GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)), new BetweenPredicate(new SymbolReference("X"), GenericLiteral.constant(INTEGER, 4L), GenericLiteral.constant(INTEGER, 7L)))).isFalse();
    }

    @Test
    public void testSymmetry()
    {
        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("a", new SymbolReference("x"))
                .put("b", new SymbolReference("y"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);

        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), new SymbolReference("a")))).isTrue();

        assertThat(verifier.process(new ComparisonExpression(LESS_THAN, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), new SymbolReference("a")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), new SymbolReference("a")))).isFalse();

        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();

        assertThat(verifier.process(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isFalse();
        assertThat(verifier.process(new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isFalse();

        assertThat(verifier.process(new ComparisonExpression(EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(NOT_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(NOT_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(NOT_EQUAL, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(NOT_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(NOT_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(NOT_EQUAL, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(NOT_EQUAL, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(NOT_EQUAL, new SymbolReference("b"), new SymbolReference("a")))).isTrue();

        assertThat(verifier.process(new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("x"), new SymbolReference("y")), new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("a"), new SymbolReference("b")))).isTrue();
        assertThat(verifier.process(new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("y"), new SymbolReference("x")), new ComparisonExpression(IS_DISTINCT_FROM, new SymbolReference("b"), new SymbolReference("a")))).isTrue();
    }
}
