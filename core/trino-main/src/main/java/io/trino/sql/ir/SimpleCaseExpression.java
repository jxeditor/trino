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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@JsonSerialize
public record SimpleCaseExpression(Expression operand, List<WhenClause> whenClauses, Optional<Expression> defaultValue)
        implements Expression
{
    public SimpleCaseExpression
    {
        requireNonNull(operand, "operand is null");
        whenClauses = ImmutableList.copyOf(whenClauses);
        requireNonNull(defaultValue, "defaultValue is null");
    }

    @Deprecated
    public Expression getOperand()
    {
        return operand;
    }

    @Deprecated
    public List<WhenClause> getWhenClauses()
    {
        return whenClauses;
    }

    @Deprecated
    public Optional<Expression> getDefaultValue()
    {
        return defaultValue;
    }

    @Override
    public <R, C> R accept(IrVisitor<R, C> visitor, C context)
    {
        return visitor.visitSimpleCaseExpression(this, context);
    }

    @Override
    public List<? extends Expression> getChildren()
    {
        ImmutableList.Builder<Expression> builder = ImmutableList.<Expression>builder()
                .add(operand);

        whenClauses.forEach(clause -> {
            builder.add(clause.getOperand());
            builder.add(clause.getResult());
        });

        defaultValue.ifPresent(builder::add);

        return builder.build();
    }
}
