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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.Assignments;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.anonymousRow;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.type.UnknownType.UNKNOWN;

public class TestPushCastIntoRow
        extends BaseRuleTest
{
    @Test
    public void test()
    {
        // anonymous row type
        test(
                new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L))), anonymousRow(BIGINT)),
                new Row(ImmutableList.of(new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT))));
        test(
                new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")))), anonymousRow(BIGINT, VARCHAR)),
                new Row(ImmutableList.of(new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT), new Cast(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")), VARCHAR))));
        test(
                new Cast(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")))), anonymousRow(SMALLINT, VARCHAR)), anonymousRow(BIGINT, VARCHAR)),
                new Row(ImmutableList.of(new Cast(new Cast(GenericLiteral.constant(INTEGER, 1L), SMALLINT), BIGINT), new Cast(new Cast(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")), VARCHAR), VARCHAR))));

        // named fields in top-level cast preserved
        test(
                new Cast(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")))), anonymousRow(SMALLINT, VARCHAR)), rowType(field("x", BIGINT), field(VARCHAR))),
                new Cast(new Row(ImmutableList.of(new Cast(GenericLiteral.constant(INTEGER, 1L), SMALLINT), new Cast(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")), VARCHAR))), rowType(field("x", BIGINT), field(VARCHAR))));
        test(
                new Cast(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")))), rowType(field("a", SMALLINT), field("b", VARCHAR))), rowType(field("x", BIGINT), field(VARCHAR))),
                new Cast(new Row(ImmutableList.of(new Cast(GenericLiteral.constant(INTEGER, 1L), SMALLINT), new Cast(GenericLiteral.constant(VARCHAR, Slices.utf8Slice("a")), VARCHAR))), rowType(field("x", BIGINT), field(VARCHAR))));

        // expression nested in another unrelated expression
        test(
                new SubscriptExpression(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L))), anonymousRow(BIGINT)), GenericLiteral.constant(INTEGER, 1L)),
                new SubscriptExpression(new Row(ImmutableList.of(new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT))), GenericLiteral.constant(INTEGER, 1L)));

        // don't insert CAST(x AS unknown)
        test(
                new Cast(new Row(ImmutableList.of(new NullLiteral())), anonymousRow(UNKNOWN)),
                new Row(ImmutableList.of(new NullLiteral())));
    }

    private void test(Expression original, Expression unwrapped)
    {
        tester().assertThat(new PushCastIntoRow().projectExpressionRewrite())
                .on(p -> p.project(
                        Assignments.builder()
                                .put(p.symbol("output"), original)
                                .build(),
                        p.values()))
                .matches(
                        project(Map.of("output", PlanMatchPattern.expression(unwrapped)),
                                values()));
    }
}
