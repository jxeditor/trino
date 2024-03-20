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
import io.trino.metadata.ResolvedFunction;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@JsonSerialize
public record FunctionCall(ResolvedFunction function, List<Expression> arguments)
        implements Expression
{
    public FunctionCall
    {
        requireNonNull(function, "function is null");
        arguments = ImmutableList.copyOf(arguments);
    }

    @Deprecated
    public ResolvedFunction getFunction()
    {
        return function;
    }

    @Deprecated
    public List<Expression> getArguments()
    {
        return arguments;
    }

    @Override
    public <R, C> R accept(IrVisitor<R, C> visitor, C context)
    {
        return visitor.visitFunctionCall(this, context);
    }

    @Override
    public List<? extends Expression> getChildren()
    {
        return arguments;
    }

    @Override
    public String toString()
    {
        return "%s(%s)".formatted(
                function.getName(),
                arguments.stream()
                        .map(Expression::toString)
                        .collect(Collectors.joining(", ")));
    }
}
