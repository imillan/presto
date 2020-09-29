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
package com.facebook.presto.operator;

import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.operator.annotations.ImplementationDependency;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;

public class ParametricFunctionHelpers
{
    private ParametricFunctionHelpers() {}

    public static MethodHandle bindDependencies(MethodHandle handle, List<ImplementationDependency> dependencies, BoundVariables variables, TypeManager typeManager, FunctionAndTypeManager functionAndTypeManager)
    {
        for (ImplementationDependency dependency : dependencies) {
            handle = MethodHandles.insertArguments(handle, 0, dependency.resolve(variables, typeManager, functionAndTypeManager));
        }
        return handle;
    }
}
