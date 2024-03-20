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
package io.trino.sql.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.errorprone.annotations.Immutable;

import java.util.List;

@Immutable
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ArithmeticBinaryExpression.class, name = "arithmeticBinary"),
        @JsonSubTypes.Type(value = ArithmeticNegation.class, name = "arithmeticUnary"),
        @JsonSubTypes.Type(value = BetweenPredicate.class, name = "between"),
        @JsonSubTypes.Type(value = BindExpression.class, name = "bind"),
        @JsonSubTypes.Type(value = Cast.class, name = "cast"),
        @JsonSubTypes.Type(value = CoalesceExpression.class, name = "coalesce"),
        @JsonSubTypes.Type(value = ComparisonExpression.class, name = "comparison"),
        @JsonSubTypes.Type(value = FunctionCall.class, name = "call"),
        @JsonSubTypes.Type(value = Constant.class, name = "constant"),
        @JsonSubTypes.Type(value = InPredicate.class, name = "in"),
        @JsonSubTypes.Type(value = IsNullPredicate.class, name = "isNull"),
        @JsonSubTypes.Type(value = LambdaExpression.class, name = "lambda"),
        @JsonSubTypes.Type(value = LogicalExpression.class, name = "logicalBinary"),
        @JsonSubTypes.Type(value = NotExpression.class, name = "not"),
        @JsonSubTypes.Type(value = NullIfExpression.class, name = "nullif"),
        @JsonSubTypes.Type(value = Row.class, name = "row"),
        @JsonSubTypes.Type(value = SearchedCaseExpression.class, name = "searchedCase"),
        @JsonSubTypes.Type(value = SimpleCaseExpression.class, name = "simpleCase"),
        @JsonSubTypes.Type(value = SubscriptExpression.class, name = "subscript"),
        @JsonSubTypes.Type(value = SymbolReference.class, name = "symbol"),
})
public sealed interface Expression
        permits ArithmeticBinaryExpression, ArithmeticNegation, BetweenPredicate,
        BindExpression, Cast, CoalesceExpression, ComparisonExpression, FunctionCall, InPredicate,
        IsNullPredicate, LambdaExpression, Constant, LogicalExpression,
        NotExpression, NullIfExpression, Row, SearchedCaseExpression, SimpleCaseExpression,
        SubscriptExpression, SymbolReference
{
    /**
     * Accessible for {@link IrVisitor}, use {@link IrVisitor#process(Expression, Object)} instead.
     */
    default <R, C> R accept(IrVisitor<R, C> visitor, C context)
    {
        return visitor.visitExpression(this, context);
    }

    @JsonIgnore
    List<? extends Expression> getChildren();
}
