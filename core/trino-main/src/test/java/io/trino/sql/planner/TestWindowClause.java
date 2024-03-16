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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.connector.SortOrder;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.ArithmeticUnaryExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.WindowNode;
import io.trino.sql.tree.QualifiedName;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.ir.ArithmeticUnaryExpression.Sign.MINUS;
import static io.trino.sql.planner.LogicalPlanner.Stage.CREATED;
import static io.trino.sql.planner.assertions.PlanMatchPattern.any;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.sort;
import static io.trino.sql.planner.assertions.PlanMatchPattern.specification;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.assertions.PlanMatchPattern.window;
import static io.trino.sql.planner.assertions.PlanMatchPattern.windowFunction;
import static io.trino.sql.planner.plan.FrameBoundType.CURRENT_ROW;
import static io.trino.sql.planner.plan.FrameBoundType.FOLLOWING;
import static io.trino.sql.planner.plan.FrameBoundType.PRECEDING;
import static io.trino.sql.planner.plan.WindowFrameType.RANGE;
import static io.trino.sql.planner.plan.WindowNode.Frame.DEFAULT_FRAME;
import static io.trino.sql.tree.SortItem.NullOrdering.LAST;
import static io.trino.sql.tree.SortItem.Ordering.ASCENDING;

public class TestWindowClause
        extends BasePlanTest
{
    @Test
    public void testPreprojectExpression()
    {
        @Language("SQL") String sql = "SELECT max(b) OVER w FROM (VALUES (1, 1)) t(a, b) WINDOW w AS (PARTITION BY a + 1)";
        PlanMatchPattern pattern =
                anyTree(
                        window(
                                windowMatcherBuilder -> windowMatcherBuilder
                                        .specification(specification(
                                                ImmutableList.of("expr"),
                                                ImmutableList.of(),
                                                ImmutableMap.of()))
                                        .addFunction(
                                                "max_result",
                                                windowFunction("max", ImmutableList.of("b"), DEFAULT_FRAME)),
                                anyTree(project(
                                        ImmutableMap.of("expr", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)))),
                                        anyTree(values("a", "b"))))));

        assertPlan(sql, CREATED, pattern);
    }

    @Test
    public void testPreprojectExpressions()
    {
        @Language("SQL") String sql = "SELECT max(b) OVER w3 FROM (VALUES (1, 1, 1)) t(a, b, c) WINDOW w1 AS (PARTITION BY a + 1), w2 AS (w1 ORDER BY b + 2), w3 AS (w2 RANGE c + 3 PRECEDING)";
        PlanMatchPattern pattern =
                anyTree(
                        window(
                                windowMatcherBuilder -> windowMatcherBuilder
                                        .specification(specification(
                                                ImmutableList.of("expr_a"),
                                                ImmutableList.of("expr_b"),
                                                ImmutableMap.of("expr_b", SortOrder.ASC_NULLS_LAST)))
                                        .addFunction(
                                                "max_result",
                                                windowFunction(
                                                        "max",
                                                        ImmutableList.of("b"),
                                                        new WindowNode.Frame(
                                                                RANGE,
                                                                PRECEDING,
                                                                Optional.of(new Symbol("frame_start")),
                                                                Optional.of(new Symbol("expr_b")),
                                                                CURRENT_ROW,
                                                                Optional.empty(),
                                                                Optional.empty()))),
                                project(
                                        ImmutableMap.of("frame_start", expression(new FunctionCall(QualifiedName.of("$operator$subtract"), ImmutableList.of(new SymbolReference("expr_b"), new SymbolReference("expr_c"))))),
                                        anyTree(project(
                                                ImmutableMap.of(
                                                        "expr_a", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L))),
                                                        "expr_b", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 2L))),
                                                        "expr_c", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("c"), GenericLiteral.constant(INTEGER, 3L)))),
                                                anyTree(values("a", "b", "c")))))));

        assertPlan(sql, CREATED, pattern);
    }

    @Test
    public void testWindowFunctionsInSelectAndOrderBy()
    {
        @Language("SQL") String sql = "SELECT array_agg(a) OVER (w ORDER BY a + 1), -a a FROM (VALUES 1, 2, 3) t(a) WINDOW w AS () ORDER BY max(a) OVER (w ORDER BY a + 1)";
        PlanMatchPattern pattern =
                anyTree(sort(
                        ImmutableList.of(sort("max_result", ASCENDING, LAST)),
                        any(window(
                                windowMatcherBuilder -> windowMatcherBuilder
                                        .specification(specification(
                                                ImmutableList.of(),
                                                ImmutableList.of("order_by_window_sortkey"),
                                                ImmutableMap.of("order_by_window_sortkey", SortOrder.ASC_NULLS_LAST)))
                                        .addFunction(
                                                "max_result",
                                                windowFunction("max", ImmutableList.of("minus_a"), DEFAULT_FRAME)),
                                any(project(
                                        ImmutableMap.of("order_by_window_sortkey", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("minus_a"), GenericLiteral.constant(INTEGER, 1L)))),
                                        project(
                                                ImmutableMap.of("minus_a", expression(new ArithmeticUnaryExpression(MINUS, new SymbolReference("a")))),
                                                window(
                                                        windowMatcherBuilder -> windowMatcherBuilder
                                                                .specification(specification(
                                                                        ImmutableList.of(),
                                                                        ImmutableList.of("select_window_sortkey"),
                                                                        ImmutableMap.of("select_window_sortkey", SortOrder.ASC_NULLS_LAST)))
                                                                .addFunction(
                                                                        "array_agg_result",
                                                                        windowFunction("array_agg", ImmutableList.of("a"), DEFAULT_FRAME)),
                                                        anyTree(project(
                                                                ImmutableMap.of("select_window_sortkey", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)))),
                                                                anyTree(values("a"))))))))))));

        assertPlan(sql, CREATED, pattern);
    }

    @Test
    public void testWindowWithFrameCoercions()
    {
        @Language("SQL") String sql = "SELECT a old_a, 2e0 a FROM (VALUES -100, -99, -98) t(a) WINDOW w AS (ORDER BY a + 1) ORDER BY count(*) OVER (w RANGE BETWEEN CURRENT ROW AND a + 1e0 FOLLOWING)";
        PlanMatchPattern pattern =
                anyTree(sort(// sort by window function result
                        ImmutableList.of(sort("count_result", ASCENDING, LAST)),
                        project(window(// window function in ORDER BY
                                windowMatcherBuilder -> windowMatcherBuilder
                                        .specification(specification(
                                                ImmutableList.of(),
                                                ImmutableList.of("sortkey"),
                                                ImmutableMap.of("sortkey", SortOrder.ASC_NULLS_LAST)))
                                        .addFunction(
                                                "count_result",
                                                windowFunction(
                                                        "count",
                                                        ImmutableList.of(),
                                                        new WindowNode.Frame(
                                                                RANGE,
                                                                CURRENT_ROW,
                                                                Optional.empty(),
                                                                Optional.empty(),
                                                                FOLLOWING,
                                                                Optional.of(new Symbol("frame_bound")),
                                                                Optional.of(new Symbol("coerced_sortkey"))))),
                                project(// frame bound value computation
                                        ImmutableMap.of("frame_bound", expression(new FunctionCall(QualifiedName.of("$operator$add"), ImmutableList.of(new SymbolReference("coerced_sortkey"), new SymbolReference("frame_offset"))))),
                                        project(// sort key coercion to frame bound type
                                                ImmutableMap.of("coerced_sortkey", expression(new Cast(new SymbolReference("sortkey"), DOUBLE))),
                                                node(FilterNode.class,
                                                        project(project(
                                                                ImmutableMap.of(
                                                                        // sort key based on "a" in source scope
                                                                        "sortkey", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L))),
                                                                        // frame offset based on "a" in output scope
                                                                        "frame_offset", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("new_a"), GenericLiteral.constant(DOUBLE, 1.0)))),
                                                                project(// output expression
                                                                        ImmutableMap.of("new_a", expression(GenericLiteral.constant(DOUBLE, 2E0))),
                                                                        project(project(values("a")))))))))))));

        assertPlan(sql, CREATED, pattern);
    }
}
