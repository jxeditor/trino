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
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.trino.metadata.TableHandle;
import io.trino.plugin.tpch.TpchColumnHandle;
import io.trino.plugin.tpch.TpchTableHandle;
import io.trino.plugin.tpch.TpchTransactionHandle;
import io.trino.spi.connector.CatalogHandle;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.TupleDomain;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IfExpression;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.trino.spi.predicate.Domain.singleValue;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MODULUS;
import static io.trino.sql.ir.BooleanLiteral.FALSE_LITERAL;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.ir.LogicalExpression.Operator.OR;
import static io.trino.sql.planner.BuiltinFunctionCallBuilder.resolve;
import static io.trino.sql.planner.assertions.PlanMatchPattern.constrainedTableScanWithTableLayout;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;

public class TestRemoveRedundantPredicateAboveTableScan
        extends BaseRuleTest
{
    private RemoveRedundantPredicateAboveTableScan removeRedundantPredicateAboveTableScan;
    private TableHandle nationTableHandle;
    private TableHandle ordersTableHandle;

    @BeforeAll
    public void setUpBeforeClass()
    {
        removeRedundantPredicateAboveTableScan = new RemoveRedundantPredicateAboveTableScan(tester().getPlannerContext(), tester().getTypeAnalyzer());
        CatalogHandle catalogHandle = tester().getCurrentCatalogHandle();
        TpchTableHandle nation = new TpchTableHandle("sf1", "nation", 1.0);
        nationTableHandle = new TableHandle(
                catalogHandle,
                nation,
                TpchTransactionHandle.INSTANCE);

        TpchTableHandle orders = new TpchTableHandle("sf1", "orders", 1.0);
        ordersTableHandle = new TableHandle(
                catalogHandle,
                orders,
                TpchTransactionHandle.INSTANCE);
    }

    @Test
    public void doesNotFireIfNoTableScan()
    {
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.values(p.symbol("a", BIGINT)))
                .doesNotFire();
    }

    @Test
    public void consumesDeterministicPredicateIfNewDomainIsSame()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 44L)),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.fromFixedValues(ImmutableMap.of(
                                        columnHandle, NullableValue.of(BIGINT, (long) 44))))))
                .matches(constrainedTableScanWithTableLayout(
                        "nation",
                        ImmutableMap.of("nationkey", singleValue(BIGINT, (long) 44)),
                        ImmutableMap.of("nationkey", "nationkey")));
    }

    @Test
    public void consumesDeterministicPredicateIfNewDomainIsWider()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(OR, ImmutableList.of(
                                new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 44L)),
                                new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 45L)))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.fromFixedValues(ImmutableMap.of(
                                        columnHandle, NullableValue.of(BIGINT, (long) 44))))))
                .matches(constrainedTableScanWithTableLayout(
                        "nation",
                        ImmutableMap.of("nationkey", singleValue(BIGINT, (long) 44)),
                        ImmutableMap.of("nationkey", "nationkey")));
    }

    @Test
    public void consumesDeterministicPredicateIfNewDomainIsNarrower()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 44L)), new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 45L)), new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 47L)))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.withColumnDomains(ImmutableMap.of(columnHandle, Domain.multipleValues(BIGINT, ImmutableList.of(44L, 45L, 46L)))))))
                .matches(
                        filter(
                                new InPredicate(new SymbolReference("nationkey"), ImmutableList.of(GenericLiteral.constant(BIGINT, 44L), GenericLiteral.constant(BIGINT, 45L))),
                                constrainedTableScanWithTableLayout(
                                        "nation",
                                        ImmutableMap.of("nationkey", Domain.multipleValues(BIGINT, ImmutableList.of(44L, 45L, 46L))),
                                        ImmutableMap.of("nationkey", "nationkey"))));
    }

    @Test
    public void doesNotConsumeRemainingPredicateIfNewDomainIsWider()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(
                                AND,
                                ImmutableList.of(
                                        new ComparisonExpression(
                                                EQUAL,
                                                resolve(tester().getMetadata())
                                                        .setName("rand")
                                                        .build(),
                                                GenericLiteral.constant(BIGINT, 42L)),
                                        new ComparisonExpression(
                                                EQUAL,
                                                new ArithmeticBinaryExpression(
                                                        MODULUS,
                                                        new SymbolReference("nationkey"),
                                                        GenericLiteral.constant(BIGINT, 17L)),
                                                GenericLiteral.constant(BIGINT, 44L)),
                                        LogicalExpression.or(
                                                new ComparisonExpression(
                                                        EQUAL,
                                                        new SymbolReference("nationkey"),
                                                        GenericLiteral.constant(BIGINT, 44L)),
                                                new ComparisonExpression(
                                                        EQUAL,
                                                        new SymbolReference("nationkey"),
                                                        GenericLiteral.constant(BIGINT, 45L))))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.fromFixedValues(ImmutableMap.of(
                                        columnHandle, NullableValue.of(BIGINT, (long) 44))))))
                .matches(
                        filter(
                                LogicalExpression.and(
                                        new ComparisonExpression(
                                                EQUAL,
                                                resolve(tester().getMetadata())
                                                        .setName("rand")
                                                        .build(),
                                                GenericLiteral.constant(BIGINT, 42L)),
                                        new ComparisonExpression(
                                                EQUAL,
                                                new ArithmeticBinaryExpression(
                                                        MODULUS,
                                                        new SymbolReference("nationkey"),
                                                        GenericLiteral.constant(BIGINT, 17L)),
                                                GenericLiteral.constant(BIGINT, 44L))),
                                constrainedTableScanWithTableLayout(
                                        "nation",
                                        ImmutableMap.of("nationkey", singleValue(BIGINT, (long) 44)),
                                        ImmutableMap.of("nationkey", "nationkey"))));
    }

    @Test
    public void doesNotFireOnNonDeterministicPredicate()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new ComparisonExpression(
                                EQUAL,
                                resolve(tester().getMetadata())
                                        .setName("rand")
                                        .build(),
                                GenericLiteral.constant(BIGINT, 42L)),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.all())))
                .doesNotFire();
    }

    @Test
    public void doesNotFireIfRuleNotChangePlan()
    {
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(MODULUS, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 17L)), GenericLiteral.constant(BIGINT, 44L)), new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(MODULUS, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 15L)), GenericLiteral.constant(BIGINT, 43L)))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), new TpchColumnHandle("nationkey", BIGINT)),
                                TupleDomain.all())))
                .doesNotFire();
    }

    @Test
    public void doesNotAddTableLayoutToFilterTableScan()
    {
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new ComparisonExpression(EQUAL, new SymbolReference("orderstatus"), new StringLiteral("F")),
                        p.tableScan(
                                ordersTableHandle,
                                ImmutableList.of(p.symbol("orderstatus", createVarcharType(1))),
                                ImmutableMap.of(p.symbol("orderstatus", createVarcharType(1)), new TpchColumnHandle("orderstatus", createVarcharType(1))))))
                .doesNotFire();
    }

    @Test
    public void doesNotFireOnNoTableScanPredicate()
    {
        ColumnHandle columnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(GREATER_THAN, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 0L)))), new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 3L)), new ComparisonExpression(LESS_THAN, new SymbolReference("nationkey"), GenericLiteral.constant(INTEGER, 1L)))))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(p.symbol("nationkey", BIGINT), columnHandle),
                                TupleDomain.all())))
                .doesNotFire();
    }

    @Test
    public void skipNotFullyExtractedConjunct()
    {
        ColumnHandle textColumnHandle = new TpchColumnHandle("name", VARCHAR);
        ColumnHandle nationKeyColumnHandle = new TpchColumnHandle("nationkey", BIGINT);
        tester().assertThat(removeRedundantPredicateAboveTableScan)
                .on(p -> p.filter(
                        new LogicalExpression(AND, ImmutableList.of(
                                new IfExpression(new ComparisonExpression(EQUAL, new SymbolReference("name"), new StringLiteral("x")), TRUE_LITERAL, FALSE_LITERAL),
                                new ComparisonExpression(EQUAL, new SymbolReference("nationkey"), GenericLiteral.constant(BIGINT, 44L)))),
                        p.tableScan(
                                nationTableHandle,
                                ImmutableList.of(
                                        p.symbol("name", VARCHAR),
                                        p.symbol("nationkey", BIGINT)),
                                ImmutableMap.of(
                                        p.symbol("name", VARCHAR), textColumnHandle,
                                        p.symbol("nationkey", BIGINT), nationKeyColumnHandle),
                                TupleDomain.fromFixedValues(ImmutableMap.of(
                                        textColumnHandle, NullableValue.of(VARCHAR, Slices.utf8Slice("value")),
                                        nationKeyColumnHandle, NullableValue.of(BIGINT, (long) 44))))))
                .matches(
                        filter(
                                new IfExpression(new ComparisonExpression(EQUAL, new SymbolReference("name"), new StringLiteral("x")), TRUE_LITERAL, FALSE_LITERAL),
                                constrainedTableScanWithTableLayout(
                                        "nation",
                                        ImmutableMap.of(
                                                "nationkey", Domain.singleValue(BIGINT, 44L),
                                                "name", Domain.singleValue(VARCHAR, Slices.utf8Slice("value"))),
                                        ImmutableMap.of(
                                                "nationkey", "nationkey",
                                                "name", "name"))));
    }
}
