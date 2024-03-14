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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

// TODO: merge with Array?
public final class InListExpression
        extends Expression
{
    private final List<Expression> values;

    @JsonCreator
    public InListExpression(List<Expression> values)
    {
        requireNonNull(values, "values is null");
        checkArgument(!values.isEmpty(), "values cannot be empty");
        this.values = ImmutableList.copyOf(values);
    }

    @JsonProperty
    public List<Expression> getValues()
    {
        return values;
    }

    @Override
    public <R, C> R accept(IrVisitor<R, C> visitor, C context)
    {
        return visitor.visitInListExpression(this, context);
    }

    @Override
    public List<? extends Expression> getChildren()
    {
        return values;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        InListExpression that = (InListExpression) o;
        return Objects.equals(values, that.values);
    }

    @Override
    public int hashCode()
    {
        return values.hashCode();
    }

    @Override
    public String toString()
    {
        return "Array[%s]".formatted(
                values.stream()
                        .map(Expression::toString)
                        .collect(Collectors.joining(", ")));
    }
}
