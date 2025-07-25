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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.expressions.DynamicFilters;
import com.facebook.presto.expressions.LogicalRowExpressions;
import com.facebook.presto.expressions.RowExpressionNodeInliner;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.EquiJoinClause;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.JoinDistributionType;
import com.facebook.presto.spi.plan.JoinNode;
import com.facebook.presto.spi.plan.JoinType;
import com.facebook.presto.spi.plan.MarkDistinctNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.SemiJoinNode;
import com.facebook.presto.spi.plan.SortNode;
import com.facebook.presto.spi.plan.SpatialJoinNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.UnionNode;
import com.facebook.presto.spi.plan.UnnestNode;
import com.facebook.presto.spi.plan.WindowNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.ExpressionOptimizer;
import com.facebook.presto.spi.relation.ExpressionOptimizerProvider;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.EffectivePredicateExtractor;
import com.facebook.presto.sql.planner.EqualityInference;
import com.facebook.presto.sql.planner.InequalityInference;
import com.facebook.presto.sql.planner.RowExpressionVariableInliner;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.VariablesExtractor;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.AssignmentUtils;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.GroupIdNode;
import com.facebook.presto.sql.planner.plan.SampleNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.relational.Expressions;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.sql.relational.RowExpressionDeterminismEvaluator;
import com.facebook.presto.sql.relational.RowExpressionDomainTranslator;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.airlift.slice.Slices;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.facebook.presto.SystemSessionProperties.shouldGenerateDomainFilters;
import static com.facebook.presto.SystemSessionProperties.shouldInferInequalityPredicates;
import static com.facebook.presto.common.function.OperatorType.BETWEEN;
import static com.facebook.presto.common.function.OperatorType.EQUAL;
import static com.facebook.presto.common.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.common.function.OperatorType.IS_DISTINCT_FROM;
import static com.facebook.presto.common.function.OperatorType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.common.function.OperatorType.NOT_EQUAL;
import static com.facebook.presto.common.function.OperatorType.negate;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.expressions.LogicalRowExpressions.FALSE_CONSTANT;
import static com.facebook.presto.expressions.LogicalRowExpressions.TRUE_CONSTANT;
import static com.facebook.presto.expressions.LogicalRowExpressions.extractConjuncts;
import static com.facebook.presto.spi.plan.JoinDistributionType.PARTITIONED;
import static com.facebook.presto.spi.plan.JoinDistributionType.REPLICATED;
import static com.facebook.presto.spi.plan.JoinType.FULL;
import static com.facebook.presto.spi.plan.JoinType.INNER;
import static com.facebook.presto.spi.plan.JoinType.LEFT;
import static com.facebook.presto.spi.plan.JoinType.RIGHT;
import static com.facebook.presto.spi.plan.ProjectNode.Locality;
import static com.facebook.presto.spi.plan.ProjectNode.Locality.LOCAL;
import static com.facebook.presto.spi.plan.ProjectNode.Locality.REMOTE;
import static com.facebook.presto.spi.plan.ProjectNode.Locality.UNKNOWN;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.planner.VariablesExtractor.extractUnique;
import static com.facebook.presto.sql.planner.plan.AssignmentUtils.identityAssignments;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.relational.Expressions.constant;
import static com.facebook.presto.sql.relational.Expressions.constantNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.filter;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

public class PredicatePushDown
        implements PlanOptimizer
{
    private final Metadata metadata;
    private final EffectivePredicateExtractor effectivePredicateExtractor;
    private final SqlParser sqlParser;
    private final RowExpressionDomainTranslator rowExpressionDomainTranslator;
    private final boolean nativeExecution;
    private final ExpressionOptimizerProvider expressionOptimizerProvider;

    public PredicatePushDown(Metadata metadata, SqlParser sqlParser, ExpressionOptimizerProvider expressionOptimizerProvider, boolean nativeExecution)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        rowExpressionDomainTranslator = new RowExpressionDomainTranslator(metadata);
        this.effectivePredicateExtractor = new EffectivePredicateExtractor(rowExpressionDomainTranslator, metadata.getFunctionAndTypeManager());
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.expressionOptimizerProvider = requireNonNull(expressionOptimizerProvider, "expressionOptimizerProvider is null");
        this.nativeExecution = nativeExecution;
    }

    @Override
    public PlanOptimizerResult optimize(PlanNode plan, Session session, TypeProvider types, VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(idAllocator, "idAllocator is null");

        Rewriter rewriter = new Rewriter(variableAllocator, idAllocator, metadata, effectivePredicateExtractor, rowExpressionDomainTranslator, expressionOptimizerProvider, sqlParser, session, nativeExecution);
        PlanNode rewrittenPlan = SimplePlanRewriter.rewriteWith(rewriter, plan, TRUE_CONSTANT);
        return PlanOptimizerResult.optimizerResult(rewrittenPlan, rewriter.isPlanChanged());
    }

    public static RowExpression createDynamicFilterExpression(String id, RowExpression input, FunctionAndTypeManager functionAndTypeManager)
    {
        return createDynamicFilterExpression(id, input, functionAndTypeManager, EQUAL.name());
    }

    private static RowExpression createDynamicFilterExpression(
            String id,
            RowExpression input,
            FunctionAndTypeManager functionAndTypeManager,
            String operator)
    {
        return call(
                functionAndTypeManager,
                DynamicFilters.DynamicFilterPlaceholderFunction.NAME,
                BooleanType.BOOLEAN,
                ImmutableList.of(
                        input,
                        new ConstantExpression(input.getSourceLocation(), Slices.utf8Slice(operator), VarcharType.VARCHAR),
                        new ConstantExpression(input.getSourceLocation(), Slices.utf8Slice(id), VarcharType.VARCHAR)));
    }

    private static class Rewriter
            extends SimplePlanRewriter<RowExpression>
    {
        private final VariableAllocator variableAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final EffectivePredicateExtractor effectivePredicateExtractor;
        private final RowExpressionDomainTranslator rowExpressionDomainTranslator;
        private final ExpressionOptimizerProvider expressionOptimizerProvider;
        private final Session session;
        private final boolean nativeExecution;
        private final ExpressionEquivalence expressionEquivalence;
        private final RowExpressionDeterminismEvaluator determinismEvaluator;
        private final LogicalRowExpressions logicalRowExpressions;
        private final FunctionAndTypeManager functionAndTypeManager;
        private final ExternalCallExpressionChecker externalCallExpressionChecker;
        private boolean planChanged;

        private Rewriter(
                VariableAllocator variableAllocator,
                PlanNodeIdAllocator idAllocator,
                Metadata metadata,
                EffectivePredicateExtractor effectivePredicateExtractor,
                RowExpressionDomainTranslator rowExpressionDomainTranslator,
                ExpressionOptimizerProvider expressionOptimizerProvider,
                SqlParser sqlParser,
                Session session,
                boolean nativeExecution)
        {
            this.variableAllocator = requireNonNull(variableAllocator, "variableAllocator is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.effectivePredicateExtractor = requireNonNull(effectivePredicateExtractor, "effectivePredicateExtractor is null");
            this.rowExpressionDomainTranslator = rowExpressionDomainTranslator;
            this.expressionOptimizerProvider = requireNonNull(expressionOptimizerProvider, "expressionOptimizerProvider is null");
            this.session = requireNonNull(session, "session is null");
            this.nativeExecution = nativeExecution;
            this.expressionEquivalence = new ExpressionEquivalence(metadata, sqlParser);
            this.determinismEvaluator = new RowExpressionDeterminismEvaluator(metadata);
            this.logicalRowExpressions = new LogicalRowExpressions(determinismEvaluator, new FunctionResolution(metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver()), metadata.getFunctionAndTypeManager());
            this.functionAndTypeManager = metadata.getFunctionAndTypeManager();
            this.externalCallExpressionChecker = new ExternalCallExpressionChecker(functionAndTypeManager);
        }

        public boolean isPlanChanged()
        {
            return planChanged;
        }

        @Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<RowExpression> context)
        {
            PlanNode rewrittenNode = context.defaultRewrite(node, TRUE_CONSTANT);
            if (!context.get().equals(TRUE_CONSTANT)) {
                // Drop in a FilterNode b/c we cannot push our predicate down any further
                planChanged = true;
                rewrittenNode = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenNode, context.get());
            }
            return rewrittenNode;
        }

        @Override
        public PlanNode visitExchange(ExchangeNode node, RewriteContext<RowExpression> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                Map<VariableReferenceExpression, VariableReferenceExpression> outputsToInputs = new HashMap<>();
                for (int index = 0; index < node.getInputs().get(i).size(); index++) {
                    outputsToInputs.put(
                            node.getOutputVariables().get(index),
                            node.getInputs().get(i).get(index));
                }

                RowExpression sourcePredicate = RowExpressionVariableInliner.inlineVariables(outputsToInputs, context.get());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source, sourcePredicate);
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                planChanged = true;
                return new ExchangeNode(
                        node.getSourceLocation(),
                        node.getId(),
                        node.getType(),
                        node.getScope(),
                        node.getPartitioningScheme(),
                        builder.build(),
                        node.getInputs(),
                        node.isEnsureSourceOrdering(),
                        node.getOrderingScheme());
            }

            return node;
        }

        @Override
        public PlanNode visitWindow(WindowNode node, RewriteContext<RowExpression> context)
        {
            // TODO: This could be broader. We can push down conjuncts if they are constant for all rows in a window partition.
            // The simplest way to guarantee this is if the conjuncts are deterministic functions of the partitioning variables.
            // This can leave out cases where they're both functions of some set of common expressions and the partitioning
            // function is injective, but that's a rare case. The majority of window nodes are expected to be partitioned by
            // pre-projected variables.
            Predicate<RowExpression> isSupported = conjunct ->
                    determinismEvaluator.isDeterministic(conjunct) &&
                            extractUnique(conjunct).stream().allMatch(node.getPartitionBy()::contains);

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(isSupported));

            PlanNode rewrittenNode = context.defaultRewrite(node, logicalRowExpressions.combineConjuncts(conjuncts.get(true)));

            if (!conjuncts.get(false).isEmpty()) {
                planChanged = true;
                rewrittenNode = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenNode, logicalRowExpressions.combineConjuncts(conjuncts.get(false)));
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> deterministicVariables = node.getAssignments().entrySet().stream()
                    .filter(entry -> determinismEvaluator.isDeterministic(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            Predicate<RowExpression> deterministic = conjunct -> deterministicVariables.containsAll(extractUnique(conjunct));

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(deterministic));

            // Push down conjuncts from the inherited predicate that only depend on deterministic assignments with
            // certain limitations.
            List<RowExpression> deterministicConjuncts = conjuncts.get(true);

            // We partition the expressions in the deterministicConjuncts into two lists, and only inline the
            // expressions that are in the inlining targets list.
            Map<Boolean, List<RowExpression>> inlineConjuncts = deterministicConjuncts.stream()
                    .collect(Collectors.partitioningBy(expression -> isInliningCandidate(expression, node)));

            List<RowExpression> inlinedDeterministicConjuncts = inlineConjuncts.get(true).stream()
                    .map(entry -> RowExpressionVariableInliner.inlineVariables(node.getAssignments().getMap(), entry))
                    .collect(Collectors.toList());

            PlanNode rewrittenNode = context.defaultRewrite(node, logicalRowExpressions.combineConjuncts(inlinedDeterministicConjuncts));

            // All deterministic conjuncts that contains non-inlining targets, and non-deterministic conjuncts,
            // if any, will be in the filter node.
            List<RowExpression> nonInliningConjuncts = inlineConjuncts.get(false);
            nonInliningConjuncts.addAll(conjuncts.get(false));

            if (!nonInliningConjuncts.isEmpty()) {
                planChanged = true;
                rewrittenNode = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenNode, logicalRowExpressions.combineConjuncts(nonInliningConjuncts));
            }

            return rewrittenNode;
        }

        private boolean isInliningCandidate(RowExpression expression, ProjectNode node)
        {
            // candidate symbols for inlining are
            //   1. references to simple constants
            //   2. references to complex expressions that appear only once
            // which come from the node, as opposed to an enclosing scope,
            // and the expression does not contain remote functions.
            Set<VariableReferenceExpression> childOutputSet = ImmutableSet.copyOf(node.getOutputVariables());
            Map<VariableReferenceExpression, Long> dependencies = VariablesExtractor.extractAll(expression).stream()
                    .filter(childOutputSet::contains)
                    .collect(Collectors.groupingBy(identity(), Collectors.counting()));

            return dependencies.entrySet().stream()
                    .allMatch(entry -> (entry.getValue() == 1 && !node.getAssignments().get(entry.getKey()).accept(new ExternalCallExpressionChecker(functionAndTypeManager), null)) ||
                            node.getAssignments().get(entry.getKey()) instanceof ConstantExpression);
        }

        @Override
        public PlanNode visitGroupId(GroupIdNode node, RewriteContext<RowExpression> context)
        {
            Map<VariableReferenceExpression, VariableReferenceExpression> commonGroupingVariableMapping = node.getGroupingColumns().entrySet().stream()
                    .filter(entry -> node.getCommonGroupingColumns().contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Predicate<RowExpression> pushdownEligiblePredicate = conjunct -> extractUnique(conjunct).stream()
                    .allMatch(commonGroupingVariableMapping.keySet()::contains);

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(pushdownEligiblePredicate));

            // Push down conjuncts from the inherited predicate that apply to common grouping symbols
            PlanNode rewrittenNode = context.defaultRewrite(node, RowExpressionVariableInliner.inlineVariables(commonGroupingVariableMapping, logicalRowExpressions.combineConjuncts(conjuncts.get(true))));

            // All other conjuncts, if any, will be in the filter node.
            if (!conjuncts.get(false).isEmpty()) {
                planChanged = true;
                rewrittenNode = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenNode, logicalRowExpressions.combineConjuncts(conjuncts.get(false)));
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitMarkDistinct(MarkDistinctNode node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> pushDownableVariables = ImmutableSet.copyOf(node.getDistinctVariables());
            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream()
                    .collect(Collectors.partitioningBy(conjunct -> pushDownableVariables.containsAll(extractUnique(conjunct))));

            PlanNode rewrittenNode = context.defaultRewrite(node, logicalRowExpressions.combineConjuncts(conjuncts.get(true)));

            if (!conjuncts.get(false).isEmpty()) {
                planChanged = true;
                rewrittenNode = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenNode, logicalRowExpressions.combineConjuncts(conjuncts.get(false)));
            }
            return rewrittenNode;
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<RowExpression> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<RowExpression> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                RowExpression sourcePredicate = RowExpressionVariableInliner.inlineVariables(node.sourceVariableMap(i), context.get());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source, sourcePredicate);
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                planChanged = true;
                return new UnionNode(node.getSourceLocation(), node.getId(), builder.build(), node.getOutputVariables(), node.getVariableMapping());
            }

            return node;
        }

        @Deprecated
        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<RowExpression> context)
        {
            PlanNode rewrittenPlan = context.rewrite(node.getSource(), logicalRowExpressions.combineConjuncts(node.getPredicate(), context.get()));
            if (!(rewrittenPlan instanceof FilterNode)) {
                planChanged = true;
                return rewrittenPlan;
            }

            FilterNode rewrittenFilterNode = (FilterNode) rewrittenPlan;
            if (!areExpressionsEquivalent(rewrittenFilterNode.getPredicate(), node.getPredicate())
                    || node.getSource() != rewrittenFilterNode.getSource()) {
                planChanged = true;
                return rewrittenPlan;
            }

            return node;
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            // See if we can rewrite outer joins in terms of a plain inner join
            node = tryNormalizeToOuterToInnerJoin(node, inheritedPredicate);

            RowExpression leftEffectivePredicate = effectivePredicateExtractor.extract(node.getLeft());
            RowExpression rightEffectivePredicate = effectivePredicateExtractor.extract(node.getRight());
            RowExpression joinPredicate = extractJoinPredicate(node);

            RowExpression leftPredicate;
            RowExpression rightPredicate;
            RowExpression postJoinPredicate;
            RowExpression newJoinPredicate;

            switch (node.getType()) {
                case INNER:
                    InnerJoinPushDownResult innerJoinPushDownResult = processInnerJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputVariables(),
                            shouldInferInequalityPredicates(session));
                    leftPredicate = innerJoinPushDownResult.getLeftPredicate();
                    rightPredicate = innerJoinPushDownResult.getRightPredicate();
                    postJoinPredicate = innerJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = innerJoinPushDownResult.getJoinPredicate();
                    break;
                case LEFT:
                    OuterJoinPushDownResult leftOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputVariables(),
                            shouldInferInequalityPredicates(session));
                    leftPredicate = leftOuterJoinPushDownResult.getOuterJoinPredicate();
                    rightPredicate = leftOuterJoinPushDownResult.getInnerJoinPredicate();
                    postJoinPredicate = leftOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = leftOuterJoinPushDownResult.getJoinPredicate();
                    break;
                case RIGHT:
                    OuterJoinPushDownResult rightOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            rightEffectivePredicate,
                            leftEffectivePredicate,
                            joinPredicate,
                            node.getRight().getOutputVariables(),
                            shouldInferInequalityPredicates(session));
                    leftPredicate = rightOuterJoinPushDownResult.getInnerJoinPredicate();
                    rightPredicate = rightOuterJoinPushDownResult.getOuterJoinPredicate();
                    postJoinPredicate = rightOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = rightOuterJoinPushDownResult.getJoinPredicate();
                    break;
                case FULL:
                    leftPredicate = TRUE_CONSTANT;
                    rightPredicate = TRUE_CONSTANT;
                    postJoinPredicate = inheritedPredicate;
                    newJoinPredicate = joinPredicate;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
            }

            newJoinPredicate = simplifyExpression(newJoinPredicate);
            // TODO: find a better way to directly optimize FALSE LITERAL in join predicate
            if (newJoinPredicate.equals(FALSE_CONSTANT)) {
                newJoinPredicate = buildEqualsExpression(functionAndTypeManager, constant(0L, BIGINT), constant(1L, BIGINT));
            }

            // Create identity projections for all existing symbols
            Assignments.Builder leftProjections = Assignments.builder()
                    .putAll(identityAssignments(node.getLeft().getOutputVariables()));

            Assignments.Builder rightProjections = Assignments.builder()
                    .putAll(identityAssignments(node.getRight().getOutputVariables()));

            Locality leftLocality = LOCAL;
            Locality rightLocality = LOCAL;
            // Create new projections for the new join clauses
            List<EquiJoinClause> equiJoinClauses = new ArrayList<>();
            ImmutableList.Builder<RowExpression> joinFilterBuilder = ImmutableList.builder();
            for (RowExpression conjunct : extractConjuncts(newJoinPredicate)) {
                if (joinEqualityExpression(node.getLeft().getOutputVariables()).test(conjunct)) {
                    boolean alignedComparison = Iterables.all(extractUnique(getLeft(conjunct)), in(node.getLeft().getOutputVariables()));
                    RowExpression leftExpression = (alignedComparison) ? getLeft(conjunct) : getRight(conjunct);
                    RowExpression rightExpression = (alignedComparison) ? getRight(conjunct) : getLeft(conjunct);

                    VariableReferenceExpression leftVariable = variableForExpression(leftExpression);
                    if (!node.getLeft().getOutputVariables().contains(leftVariable)) {
                        leftProjections.put(leftVariable, leftExpression);
                        if (leftExpression.accept(externalCallExpressionChecker, null)) {
                            leftLocality = REMOTE;
                        }
                    }

                    VariableReferenceExpression rightVariable = variableForExpression(rightExpression);
                    if (!node.getRight().getOutputVariables().contains(rightVariable)) {
                        rightProjections.put(rightVariable, rightExpression);
                        if (rightExpression.accept(externalCallExpressionChecker, null)) {
                            rightLocality = REMOTE;
                        }
                    }

                    equiJoinClauses.add(new EquiJoinClause(leftVariable, rightVariable));
                }
                else {
                    joinFilterBuilder.add(conjunct);
                }
            }

            PlanNode leftSource;
            PlanNode rightSource;

            List<RowExpression> joinFilter = joinFilterBuilder.build();
            boolean dynamicFilterEnabled = isEnableDynamicFiltering();
            Map<String, VariableReferenceExpression> dynamicFilters = ImmutableMap.of();
            if (dynamicFilterEnabled) {
                DynamicFiltersResult dynamicFiltersResult = createDynamicFilters(node, equiJoinClauses, joinFilter, idAllocator, metadata.getFunctionAndTypeManager());
                dynamicFilters = dynamicFiltersResult.getDynamicFilters();
                leftPredicate = logicalRowExpressions.combineConjuncts(leftPredicate, logicalRowExpressions.combineConjuncts(dynamicFiltersResult.getPredicates()));
            }

            boolean equiJoinClausesUnmodified = ImmutableSet.copyOf(equiJoinClauses).equals(ImmutableSet.copyOf(node.getCriteria()));

            if (dynamicFilterEnabled && !equiJoinClausesUnmodified) {
                leftSource = context.rewrite(wrapInProjectIfNeeded(node.getLeft(), leftProjections.build()), leftPredicate);
                rightSource = context.rewrite(wrapInProjectIfNeeded(node.getRight(), rightProjections.build()), rightPredicate);
            }
            else {
                leftSource = context.rewrite(node.getLeft(), leftPredicate);
                rightSource = context.rewrite(node.getRight(), rightPredicate);
            }

            Optional<RowExpression> newJoinFilter = Optional.of(logicalRowExpressions.combineConjuncts(joinFilter));
            if (newJoinFilter.get() == TRUE_CONSTANT) {
                newJoinFilter = Optional.empty();
            }

            if (node.getType() == INNER && newJoinFilter.isPresent() && equiJoinClauses.isEmpty()) {
                // if we do not have any equi conjunct we do not pushdown non-equality condition into
                // inner join, so we plan execution as nested-loops-join followed by filter instead
                // hash join.
                // todo: remove the code when we have support for filter function in nested loop join
                postJoinPredicate = logicalRowExpressions.combineConjuncts(postJoinPredicate, newJoinFilter.get());
                newJoinFilter = Optional.empty();
            }

            boolean filtersEquivalent =
                    newJoinFilter.isPresent() == node.getFilter().isPresent() &&
                            (!newJoinFilter.isPresent() || areExpressionsEquivalent(newJoinFilter.get(), node.getFilter().get()));

            PlanNode output = node;
            if (leftSource != node.getLeft() ||
                    rightSource != node.getRight() ||
                    !filtersEquivalent ||
                    (dynamicFilterEnabled && !dynamicFilters.equals(node.getDynamicFilters())) ||
                    !equiJoinClausesUnmodified) {
                leftSource = wrapInProjectIfNeeded(leftSource, leftProjections.build(), leftLocality);
                rightSource = wrapInProjectIfNeeded(rightSource, rightProjections.build(), rightLocality);

                checkState(ImmutableSet.<VariableReferenceExpression>builder()
                                .addAll(leftSource.getOutputVariables())
                                .addAll(rightSource.getOutputVariables())
                                .build().containsAll(node.getOutputVariables()),
                        "JoinNode predicate pushdown incorrect : Left and right source are not producing original JoinNode output variables");

                // if the distribution type is already set, make sure that changes from PredicatePushDown
                // don't make the join node invalid.
                Optional<JoinDistributionType> distributionType = node.getDistributionType();
                if (node.getDistributionType().isPresent()) {
                    if (node.getType().mustPartition()) {
                        distributionType = Optional.of(PARTITIONED);
                    }
                    if (node.getType().mustReplicate(equiJoinClauses)) {
                        distributionType = Optional.of(REPLICATED);
                    }
                }

                List<VariableReferenceExpression> newJoinOutputVariables = node.getOutputVariables();
                // If, the new Join node is a cross-join OR
                // we have a post join predicate that refers to variables that were not already referenced by the JoinNode
                if ((node.getType() == INNER && equiJoinClauses.isEmpty() && !newJoinFilter.isPresent())
                        || (!ImmutableSet.copyOf(newJoinOutputVariables).containsAll(extractUnique(postJoinPredicate)))) {
                    // Set the new output variables to be left + right output variables
                    newJoinOutputVariables = ImmutableList.<VariableReferenceExpression>builder()
                            .addAll(leftSource.getOutputVariables())
                            .addAll(rightSource.getOutputVariables())
                            .build();
                }

                planChanged = true;
                output = new JoinNode(
                        node.getSourceLocation(),
                        node.getId(),
                        node.getType(),
                        leftSource,
                        rightSource,
                        equiJoinClauses,
                        newJoinOutputVariables,
                        newJoinFilter,
                        node.getLeftHashVariable(),
                        node.getRightHashVariable(),
                        distributionType,
                        dynamicFilters);
            }

            if (!postJoinPredicate.equals(TRUE_CONSTANT)) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, postJoinPredicate);
            }

            if (!node.getOutputVariables().equals(output.getOutputVariables())) {
                planChanged = true;
                output = new ProjectNode(node.getSourceLocation(), idAllocator.getNextId(), output, identityAssignments(node.getOutputVariables()), LOCAL);
            }

            return output;
        }

        private PlanNode wrapInProjectIfNeeded(PlanNode childNode, Assignments assignments)
        {
            return wrapInProjectIfNeeded(childNode, assignments, UNKNOWN);
        }

        private PlanNode wrapInProjectIfNeeded(PlanNode childNode, Assignments assignments, Locality locality)
        {
            if ((childNode instanceof ProjectNode || childNode instanceof JoinNode)
                    && AssignmentUtils.isIdentity(assignments)) {
                // By wrapping an identity Project over a child node of type :
                // ProjectNode - we are adding no value
                // JoinNode - we are preventing this JoinNode from participating in join re-ordering
                // So we return the child node as is, without an identity project
                return childNode;
            }

            return new ProjectNode(childNode.getSourceLocation(), idAllocator.getNextId(), childNode, assignments, locality);
        }

        private static DynamicFiltersResult createDynamicFilters(
                JoinNode node,
                List<EquiJoinClause> equiJoinClauses,
                List<RowExpression> joinFilter,
                PlanNodeIdAllocator idAllocator,
                FunctionAndTypeManager functionAndTypeManager)
        {
            Map<String, VariableReferenceExpression> dynamicFilters = ImmutableMap.of();
            List<RowExpression> predicates = ImmutableList.of();
            if (node.getType() == INNER || node.getType() == RIGHT) {
                List<CallExpression> clauses = getDynamicFilterClauses(node, equiJoinClauses, joinFilter, functionAndTypeManager);
                List<VariableReferenceExpression> buildSymbols = clauses.stream()
                        .map(expression -> (VariableReferenceExpression) expression.getArguments().get(1))
                        .collect(Collectors.toList());

                BiMap<VariableReferenceExpression, String> buildSymbolToIdMap = HashBiMap.create(node.getDynamicFilters()).inverse();
                for (VariableReferenceExpression buildSymbol : buildSymbols) {
                    buildSymbolToIdMap.put(buildSymbol, idAllocator.getNextId().toString());
                }

                ImmutableList.Builder<RowExpression> predicatesBuilder = ImmutableList.builder();
                for (CallExpression expression : clauses) {
                    RowExpression probeExpression = expression.getArguments().get(0);
                    VariableReferenceExpression buildSymbol = (VariableReferenceExpression) expression.getArguments().get(1);
                    String id = buildSymbolToIdMap.get(buildSymbol);
                    RowExpression predicate = createDynamicFilterExpression(id, probeExpression, functionAndTypeManager, expression.getDisplayName());
                    predicatesBuilder.add(predicate);
                }
                dynamicFilters = buildSymbolToIdMap.inverse();
                predicates = predicatesBuilder.build();
            }
            return new DynamicFiltersResult(dynamicFilters, predicates);
        }

        private static List<CallExpression> getDynamicFilterClauses(
                JoinNode node,
                List<EquiJoinClause> equiJoinClauses,
                List<RowExpression> joinFilter,
                FunctionAndTypeManager functionAndTypeManager)
        {
            // New equiJoinClauses could potentially not contain symbols used in current dynamic filters.
            // Since we use PredicatePushdown to push dynamic filters themselves,
            // instead of separate ApplyDynamicFilters rule we derive dynamic filters within PredicatePushdown itself.
            // Even if equiJoinClauses.equals(node.getCriteria), current dynamic filters may not match equiJoinClauses
            ImmutableList.Builder<CallExpression> clausesBuilder = ImmutableList.builder();
            for (EquiJoinClause clause : equiJoinClauses) {
                VariableReferenceExpression probeSymbol = clause.getLeft();
                VariableReferenceExpression buildSymbol = clause.getRight();
                clausesBuilder.add(call(
                        EQUAL.name(),
                        functionAndTypeManager.resolveOperator(EQUAL, fromTypes(probeSymbol.getType(), buildSymbol.getType())),
                        BOOLEAN,
                        probeSymbol,
                        buildSymbol));
            }

            for (RowExpression filter : joinFilter) {
                if ((filter instanceof CallExpression)) {
                    CallExpression call = (CallExpression) filter;
                    List<RowExpression> arguments = call.getArguments();

                    // TODO: support for complex inequalities, e.g. left < right + 10, NOT, LIKE
                    if (arguments.size() == 1) {
                        continue;
                    }

                    if (arguments.size() == 3) {
                        // try convert BETWEEN into GREATER_THAN_OR_EQUAL and LESS_THAN_OR_EQUAL
                        String function = call.getDisplayName();
                        if (function.equals(BETWEEN.name()) && arguments.get(0) instanceof VariableReferenceExpression) {
                            if (arguments.get(1) instanceof VariableReferenceExpression) {
                                CallExpression callExpression = call(
                                        GREATER_THAN_OR_EQUAL.name(),
                                        functionAndTypeManager.resolveOperator(GREATER_THAN_OR_EQUAL, fromTypes(arguments.get(0).getType(), arguments.get(1).getType())),
                                        BOOLEAN,
                                        arguments.get(0),
                                        arguments.get(1));
                                Optional<CallExpression> comparisonExpression = getDynamicFilterComparison(node, callExpression, functionAndTypeManager);
                                if (comparisonExpression.isPresent()) {
                                    clausesBuilder.add(comparisonExpression.get());
                                }
                            }
                            if (arguments.get(2) instanceof VariableReferenceExpression) {
                                CallExpression callExpression = call(
                                        LESS_THAN_OR_EQUAL.name(),
                                        functionAndTypeManager.resolveOperator(LESS_THAN_OR_EQUAL, fromTypes(arguments.get(0).getType(), arguments.get(2).getType())),
                                        BOOLEAN,
                                        arguments.get(0),
                                        arguments.get(2));
                                Optional<CallExpression> comparisonExpression = getDynamicFilterComparison(node, callExpression, functionAndTypeManager);
                                if (comparisonExpression.isPresent()) {
                                    clausesBuilder.add(comparisonExpression.get());
                                }
                            }
                        }
                        continue;
                    }

                    checkArgument(arguments.size() == 2, "invalid arguments count: %s", arguments.size());
                    Optional<CallExpression> comparisonExpression = getDynamicFilterComparison(node, call, functionAndTypeManager);
                    if (comparisonExpression.isPresent()) {
                        clausesBuilder.add(comparisonExpression.get());
                    }
                }
            }
            return clausesBuilder.build();
        }

        private static Optional<CallExpression> getDynamicFilterComparison(
                JoinNode node,
                CallExpression call,
                FunctionAndTypeManager functionAndTypeManager)
        {
            Optional<OperatorType> operatorType = functionAndTypeManager.getFunctionMetadata(call.getFunctionHandle()).getOperatorType();
            if (!operatorType.isPresent()) {
                return Optional.empty();
            }
            OperatorType operator = operatorType.get();
            List<RowExpression> arguments = call.getArguments();
            RowExpression left = arguments.get(0);
            RowExpression right = arguments.get(1);

            // supported comparison for dynamic filtering: EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL
            if (!operator.isComparisonOperator()) {
                return Optional.empty();
            }
            if (operator == NOT_EQUAL || operator == IS_DISTINCT_FROM) {
                return Optional.empty();
            }
            // supported expression for dynamic filtering:
            // either 1. left child contains left variables and right child contains right variables
            // or, 2. left child contains right variables and right child contains left variables
            Set<VariableReferenceExpression> leftUniqueOutputs = extractUnique(left);
            Set<VariableReferenceExpression> rightUniqueOutputs = extractUnique(right);
            boolean leftChildContainsLeftVariables = node.getLeft().getOutputVariables().containsAll(leftUniqueOutputs);
            boolean rightChildContainsRightVariables = node.getRight().getOutputVariables().containsAll(rightUniqueOutputs);
            boolean leftChildContainsRightVariables = node.getLeft().getOutputVariables().containsAll(rightUniqueOutputs);
            boolean rightChildContainsLeftVariables = node.getRight().getOutputVariables().containsAll(leftUniqueOutputs);
            if (!((leftChildContainsLeftVariables && rightChildContainsRightVariables) || (leftChildContainsRightVariables && rightChildContainsLeftVariables))) {
                return Optional.empty();
            }

            boolean shouldFlip = false;
            if (leftChildContainsRightVariables && rightChildContainsLeftVariables) {
                shouldFlip = true;
            }

            if (shouldFlip) {
                operator = negate(operator);
                left = arguments.get(1);
                right = arguments.get(0);
            }

            if (!(right instanceof VariableReferenceExpression)) {
                return Optional.empty();
            }
            return Optional.of(call(
                    operator.name(),
                    functionAndTypeManager.resolveOperator(operator, fromTypes(left.getType(), right.getType())),
                    BOOLEAN,
                    left,
                    right));
        }

        private static DynamicFiltersResult createDynamicFilters(
                VariableReferenceExpression probeVariable,
                VariableReferenceExpression buildVariable,
                PlanNodeIdAllocator idAllocator,
                FunctionAndTypeManager functionAndTypeManager)
        {
            ImmutableMap.Builder<String, VariableReferenceExpression> dynamicFiltersBuilder = ImmutableMap.builder();
            ImmutableList.Builder<RowExpression> predicatesBuilder = ImmutableList.builder();
            String id = idAllocator.getNextId().toString();
            predicatesBuilder.add(createDynamicFilterExpression(id, probeVariable, functionAndTypeManager));
            dynamicFiltersBuilder.put(id, buildVariable);
            return new DynamicFiltersResult(dynamicFiltersBuilder.build(), predicatesBuilder.build());
        }

        private static class DynamicFiltersResult
        {
            private final Map<String, VariableReferenceExpression> dynamicFilters;
            private final List<RowExpression> predicates;

            public DynamicFiltersResult(Map<String, VariableReferenceExpression> dynamicFilters, List<RowExpression> predicates)
            {
                this.dynamicFilters = dynamicFilters;
                this.predicates = predicates;
            }

            public Map<String, VariableReferenceExpression> getDynamicFilters()
            {
                return dynamicFilters;
            }

            public List<RowExpression> getPredicates()
            {
                return predicates;
            }
        }

        private static RowExpression getLeft(RowExpression expression)
        {
            checkArgument(expression instanceof CallExpression && ((CallExpression) expression).getArguments().size() == 2, "must be binary call expression");
            return ((CallExpression) expression).getArguments().get(0);
        }

        private static RowExpression getRight(RowExpression expression)
        {
            checkArgument(expression instanceof CallExpression && ((CallExpression) expression).getArguments().size() == 2, "must be binary call expression");
            return ((CallExpression) expression).getArguments().get(1);
        }

        @Override
        public PlanNode visitSpatialJoin(SpatialJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            // See if we can rewrite left join in terms of a plain inner join
            if (node.getType() == SpatialJoinNode.Type.LEFT && canConvertOuterToInner(node.getRight().getOutputVariables(), inheritedPredicate)) {
                planChanged = true;
                node = new SpatialJoinNode(
                        node.getSourceLocation(),
                        node.getId(),
                        SpatialJoinNode.Type.INNER,
                        node.getLeft(),
                        node.getRight(),
                        node.getOutputVariables(),
                        node.getFilter(),
                        node.getLeftPartitionVariable(),
                        node.getRightPartitionVariable(),
                        node.getKdbTree());
            }

            RowExpression leftEffectivePredicate = effectivePredicateExtractor.extract(node.getLeft());
            RowExpression rightEffectivePredicate = effectivePredicateExtractor.extract(node.getRight());
            RowExpression joinPredicate = node.getFilter();

            RowExpression leftPredicate;
            RowExpression rightPredicate;
            RowExpression postJoinPredicate;
            RowExpression newJoinPredicate;

            switch (node.getType()) {
                case INNER:
                    InnerJoinPushDownResult innerJoinPushDownResult = processInnerJoin(
                            inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputVariables(),
                            shouldInferInequalityPredicates(session));
                    leftPredicate = innerJoinPushDownResult.getLeftPredicate();
                    rightPredicate = innerJoinPushDownResult.getRightPredicate();
                    postJoinPredicate = innerJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = innerJoinPushDownResult.getJoinPredicate();
                    break;
                case LEFT:
                    OuterJoinPushDownResult leftOuterJoinPushDownResult = processLimitedOuterJoin(
                            inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputVariables(),
                            shouldInferInequalityPredicates(session));
                    leftPredicate = leftOuterJoinPushDownResult.getOuterJoinPredicate();
                    rightPredicate = leftOuterJoinPushDownResult.getInnerJoinPredicate();
                    postJoinPredicate = leftOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = leftOuterJoinPushDownResult.getJoinPredicate();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported spatial join type: " + node.getType());
            }

            newJoinPredicate = simplifyExpression(newJoinPredicate);
            verify(!newJoinPredicate.equals(FALSE_CONSTANT), "Spatial join predicate is missing");

            PlanNode leftSource = context.rewrite(node.getLeft(), leftPredicate);
            PlanNode rightSource = context.rewrite(node.getRight(), rightPredicate);

            PlanNode output = node;
            if (leftSource != node.getLeft() ||
                    rightSource != node.getRight() ||
                    !areExpressionsEquivalent(newJoinPredicate, joinPredicate)) {
                // Create identity projections for all existing symbols
                Assignments.Builder leftProjections = Assignments.builder()
                        .putAll(identityAssignments(node.getLeft().getOutputVariables()));

                Assignments.Builder rightProjections = Assignments.builder()
                        .putAll(identityAssignments(node.getRight().getOutputVariables()));

                leftSource = new ProjectNode(node.getSourceLocation(), idAllocator.getNextId(), leftSource, leftProjections.build(), LOCAL);
                rightSource = new ProjectNode(node.getSourceLocation(), idAllocator.getNextId(), rightSource, rightProjections.build(), LOCAL);

                planChanged = true;
                output = new SpatialJoinNode(
                        node.getSourceLocation(),
                        node.getId(),
                        node.getType(),
                        leftSource,
                        rightSource,
                        node.getOutputVariables(),
                        newJoinPredicate,
                        node.getLeftPartitionVariable(),
                        node.getRightPartitionVariable(),
                        node.getKdbTree());
            }

            if (!postJoinPredicate.equals(TRUE_CONSTANT)) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, postJoinPredicate);
            }

            return output;
        }

        private VariableReferenceExpression variableForExpression(RowExpression expression)
        {
            if (expression instanceof VariableReferenceExpression) {
                return (VariableReferenceExpression) expression;
            }

            return variableAllocator.newVariable(expression);
        }

        private OuterJoinPushDownResult processLimitedOuterJoin(RowExpression inheritedPredicate,
                RowExpression outerEffectivePredicate,
                RowExpression innerEffectivePredicate,
                RowExpression joinPredicate,
                Collection<VariableReferenceExpression> outerVariables,
                boolean inferInequalityPredicates)
        {
            checkArgument(Iterables.all(extractUnique(outerEffectivePredicate), in(outerVariables)), "outerEffectivePredicate must only contain variables from outerVariables");
            checkArgument(Iterables.all(extractUnique(innerEffectivePredicate), not(in(outerVariables))), "innerEffectivePredicate must not contain variables from outerVariables");

            ImmutableList.Builder<RowExpression> outerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> innerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> postJoinConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> joinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            postJoinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic)));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            outerEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(outerEffectivePredicate);
            innerEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(innerEffectivePredicate);
            joinConjuncts.addAll(filter(extractConjuncts(joinPredicate), not(determinismEvaluator::isDeterministic)));
            joinPredicate = logicalRowExpressions.filterDeterministicConjuncts(joinPredicate);

            // Generate equality inferences
            EqualityInference inheritedInference = createEqualityInference(inheritedPredicate);
            EqualityInference outerInference = createEqualityInference(inheritedPredicate, outerEffectivePredicate);

            EqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(in(outerVariables));
            RowExpression outerOnlyInheritedEqualities = logicalRowExpressions.combineConjuncts(equalityPartition.getScopeEqualities());
            EqualityInference potentialNullSymbolInference = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, innerEffectivePredicate, joinPredicate);

            // Generate inequality inferences
            if (inferInequalityPredicates) {
                InequalityInference inequalityInference = new InequalityInference.Builder(functionAndTypeManager, expressionEquivalence, Optional.of(outerVariables))
                        .addInequalityInferences(joinPredicate, inheritedPredicate)
                        .build();
                innerPushdownConjuncts.addAll(inequalityInference.inferInequalities());
            }

            // See if we can push inherited predicates down
            for (RowExpression conjunct : nonInferableConjuncts(inheritedPredicate)) {
                RowExpression outerRewritten = outerInference.rewriteExpression(conjunct, in(outerVariables));
                if (outerRewritten != null) {
                    outerPushdownConjuncts.add(outerRewritten);

                    // A conjunct can only be pushed down into an inner side if it can be rewritten in terms of the outer side
                    RowExpression innerRewritten = potentialNullSymbolInference.rewriteExpression(outerRewritten, not(in(outerVariables)));
                    if (innerRewritten != null) {
                        innerPushdownConjuncts.add(innerRewritten);
                    }
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            if (shouldGenerateDomainFilters(session)) {
                // Extract domains for each of the variables from the inherited predicate
                // See related comment on #processInnerJoin
                rowExpressionDomainTranslator.fromPredicate(session.toConnectorSession(), inheritedPredicate)
                        .getTupleDomain()
                        .getDomains()
                        .ifPresent(map -> map.forEach((variable, domain) -> {
                            // For outer-side, inferred domains can be pushed down as-is
                            if (outerVariables.contains(variable)) {
                                outerPushdownConjuncts.add(rowExpressionDomainTranslator.toPredicate(domain, variable));
                            }
                            // For inner-side, only domains that don't include NULL can be pushed down
                            else if (!domain.isNullAllowed()) {
                                innerPushdownConjuncts.add(rowExpressionDomainTranslator.toPredicate(domain, variable));
                            }
                        }));
            }

            // Add the equalities from the inferences back in
            outerPushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            // See if we can push down any outer effective predicates to the inner side
            for (RowExpression conjunct : nonInferableConjuncts(outerEffectivePredicate)) {
                RowExpression rewritten = potentialNullSymbolInference.rewriteExpression(conjunct, not(in(outerVariables)));
                if (rewritten != null) {
                    innerPushdownConjuncts.add(rewritten);
                }
            }

            // See if we can push down join predicates to the inner side
            for (RowExpression conjunct : nonInferableConjuncts(joinPredicate)) {
                RowExpression innerRewritten = potentialNullSymbolInference.rewriteExpression(conjunct, not(in(outerVariables)));
                if (innerRewritten != null) {
                    innerPushdownConjuncts.add(innerRewritten);
                }
                else {
                    joinConjuncts.add(conjunct);
                }
            }

            // Push outer and join equalities into the inner side. For example:
            // SELECT * FROM nation LEFT OUTER JOIN region ON nation.regionkey = region.regionkey and nation.name = region.name WHERE nation.name = 'blah'

            EqualityInference potentialNullSymbolInferenceWithoutInnerInferred = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, joinPredicate);
            innerPushdownConjuncts.addAll(potentialNullSymbolInferenceWithoutInnerInferred.generateEqualitiesPartitionedBy(not(in(outerVariables))).getScopeEqualities());

            // TODO: we can further improve simplifying the equalities by considering other relationships from the outer side
            EqualityInference.EqualityPartition joinEqualityPartition = createEqualityInference(joinPredicate).generateEqualitiesPartitionedBy(not(in(outerVariables)));
            innerPushdownConjuncts.addAll(joinEqualityPartition.getScopeEqualities());
            joinConjuncts.addAll(joinEqualityPartition.getScopeComplementEqualities())
                    .addAll(joinEqualityPartition.getScopeStraddlingEqualities());

            return new OuterJoinPushDownResult(logicalRowExpressions.combineConjuncts(outerPushdownConjuncts.build()),
                    logicalRowExpressions.combineConjuncts(innerPushdownConjuncts.build()),
                    logicalRowExpressions.combineConjuncts(joinConjuncts.build()),
                    logicalRowExpressions.combineConjuncts(postJoinConjuncts.build()));
        }

        private static class OuterJoinPushDownResult
        {
            private final RowExpression outerJoinPredicate;
            private final RowExpression innerJoinPredicate;
            private final RowExpression joinPredicate;
            private final RowExpression postJoinPredicate;

            private OuterJoinPushDownResult(RowExpression outerJoinPredicate, RowExpression innerJoinPredicate, RowExpression joinPredicate, RowExpression postJoinPredicate)
            {
                this.outerJoinPredicate = outerJoinPredicate;
                this.innerJoinPredicate = innerJoinPredicate;
                this.joinPredicate = joinPredicate;
                this.postJoinPredicate = postJoinPredicate;
            }

            private RowExpression getOuterJoinPredicate()
            {
                return outerJoinPredicate;
            }

            private RowExpression getInnerJoinPredicate()
            {
                return innerJoinPredicate;
            }

            public RowExpression getJoinPredicate()
            {
                return joinPredicate;
            }

            private RowExpression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private InnerJoinPushDownResult processInnerJoin(
                RowExpression inheritedPredicate,
                RowExpression leftEffectivePredicate,
                RowExpression rightEffectivePredicate,
                RowExpression joinPredicate,
                Collection<VariableReferenceExpression> leftVariables,
                boolean inferInequalityPredicates)
        {
            checkArgument(Iterables.all(extractUnique(leftEffectivePredicate), in(leftVariables)), "leftEffectivePredicate must only contain variables from leftVariables");
            checkArgument(Iterables.all(extractUnique(rightEffectivePredicate), not(in(leftVariables))), "rightEffectivePredicate must not contain variables from leftVariables");

            ImmutableList.Builder<RowExpression> leftPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> rightPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> joinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            joinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic)));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            joinConjuncts.addAll(filter(extractConjuncts(joinPredicate), not(determinismEvaluator::isDeterministic)));
            joinPredicate = logicalRowExpressions.filterDeterministicConjuncts(joinPredicate);

            leftEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(leftEffectivePredicate);
            rightEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(rightEffectivePredicate);

            // Generate inequality inferences
            if (inferInequalityPredicates) {
                InequalityInference inequalityInference = new InequalityInference.Builder(functionAndTypeManager, expressionEquivalence, Optional.empty())
                        .addInequalityInferences(joinPredicate, inheritedPredicate)
                        .build();
                joinConjuncts.addAll(inequalityInference.inferInequalities());
            }

            // Generate equality inferences
            EqualityInference allInference = new EqualityInference.Builder(functionAndTypeManager)
                    .addEqualityInference(inheritedPredicate, leftEffectivePredicate, rightEffectivePredicate, joinPredicate)
                    .build();
            EqualityInference allInferenceWithoutLeftInferred = new EqualityInference.Builder(functionAndTypeManager)
                    .addEqualityInference(inheritedPredicate, rightEffectivePredicate, joinPredicate)
                    .build();
            EqualityInference allInferenceWithoutRightInferred = new EqualityInference.Builder(functionAndTypeManager)
                    .addEqualityInference(inheritedPredicate, leftEffectivePredicate, joinPredicate)
                    .build();

            // Sort through conjuncts in inheritedPredicate that were not used for inference
            for (RowExpression conjunct : new EqualityInference.Builder(functionAndTypeManager).nonInferableConjuncts(inheritedPredicate)) {
                RowExpression leftRewrittenConjunct = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (leftRewrittenConjunct != null) {
                    leftPushDownConjuncts.add(leftRewrittenConjunct);
                }

                RowExpression rightRewrittenConjunct = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rightRewrittenConjunct != null) {
                    rightPushDownConjuncts.add(rightRewrittenConjunct);
                }

                // Drop predicate after join only if unable to push down to either side
                if (leftRewrittenConjunct == null && rightRewrittenConjunct == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            // See if we can push the right effective predicate to the left side
            for (RowExpression conjunct : new EqualityInference.Builder(functionAndTypeManager).nonInferableConjuncts(rightEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (rewritten != null) {
                    leftPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push the left effective predicate to the right side
            for (RowExpression conjunct : new EqualityInference.Builder(functionAndTypeManager).nonInferableConjuncts(leftEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rewritten != null) {
                    rightPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push any parts of the join predicates to either side
            for (RowExpression conjunct : new EqualityInference.Builder(functionAndTypeManager).nonInferableConjuncts(joinPredicate)) {
                RowExpression leftRewritten = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (leftRewritten != null) {
                    leftPushDownConjuncts.add(leftRewritten);
                }

                RowExpression rightRewritten = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rightRewritten != null) {
                    rightPushDownConjuncts.add(rightRewritten);
                }

                if (leftRewritten == null && rightRewritten == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            if (shouldGenerateDomainFilters(session)) {
                // Extract domains for each of the variables from the inherited predicate
                // and translate them back to predicates that can be added to the sources
                // These prove to be helpful to extract predicates on columns that cannot be converted to CNF cleanly
                // E.g. for [(Left=4 AND Right=20) or (Left=5 AND Right=21) or (Left=6 AND Right=22)]
                // We would extract the TupleDomain = [ Left IN (4,5,6), Right IN (20,21,22)], then convert them into predicates on 'Left' & 'Right'

                // Note that, we can end up adding logically equivalent duplicate conjuncts for some variables
                // These are usually eliminated during later stages of predicate simplification. However, if some remain
                // these redundant predicates do end up increasing plan node cost (with no impact to correctness)
                // TODO : Move this filter addition as a cost-based rule if/when we implement a true CBO
                rowExpressionDomainTranslator.fromPredicate(session.toConnectorSession(), inheritedPredicate)
                        .getTupleDomain()
                        .getDomains()
                        .ifPresent(map -> map.forEach((variable, domain) -> {
                            if (leftVariables.contains(variable)) {
                                leftPushDownConjuncts.add(rowExpressionDomainTranslator.toPredicate(domain, variable));
                            }
                            else {
                                rightPushDownConjuncts.add(rowExpressionDomainTranslator.toPredicate(domain, variable));
                            }
                        }));
            }

            // Add equalities from the inference back in
            leftPushDownConjuncts.addAll(allInferenceWithoutLeftInferred.generateEqualitiesPartitionedBy(in(leftVariables)).getScopeEqualities());
            rightPushDownConjuncts.addAll(allInferenceWithoutRightInferred.generateEqualitiesPartitionedBy(not(in(leftVariables))).getScopeEqualities());
            joinConjuncts.addAll(allInference.generateEqualitiesPartitionedBy(in(leftVariables)::apply).getScopeStraddlingEqualities()); // scope straddling equalities get dropped in as part of the join predicate

            return new Rewriter.InnerJoinPushDownResult(
                    expressionOptimizerProvider,
                    logicalRowExpressions.combineConjuncts(leftPushDownConjuncts.build()),
                    logicalRowExpressions.combineConjuncts(rightPushDownConjuncts.build()),
                    logicalRowExpressions.combineConjuncts(joinConjuncts.build()), TRUE_CONSTANT);
        }

        private static class InnerJoinPushDownResult
        {
            private final RowExpression leftPredicate;
            private final RowExpression rightPredicate;
            private final RowExpression joinPredicate;
            private final RowExpression postJoinPredicate;
            private final ExpressionOptimizerProvider expressionOptimizerProvider;

            private InnerJoinPushDownResult(
                    ExpressionOptimizerProvider expressionOptimizerProvider,
                    RowExpression leftPredicate,
                    RowExpression rightPredicate,
                    RowExpression joinPredicate,
                    RowExpression postJoinPredicate)
            {
                this.expressionOptimizerProvider = requireNonNull(expressionOptimizerProvider, "expressionOptimizerProvider is null");
                this.leftPredicate = requireNonNull(leftPredicate, "leftPredicate is null");
                this.rightPredicate = requireNonNull(rightPredicate, "rightPredicate is null");
                this.joinPredicate = requireNonNull(joinPredicate, "joinPredicate is null");
                this.postJoinPredicate = requireNonNull(postJoinPredicate, "postJoinPredicate is null");
            }

            private RowExpression getLeftPredicate()
            {
                return leftPredicate;
            }

            private RowExpression getRightPredicate()
            {
                return rightPredicate;
            }

            private RowExpression getJoinPredicate()
            {
                return joinPredicate;
            }

            private RowExpression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private RowExpression extractJoinPredicate(JoinNode joinNode)
        {
            ImmutableList.Builder<RowExpression> builder = ImmutableList.builder();
            for (EquiJoinClause equiJoinClause : joinNode.getCriteria()) {
                builder.add(toRowExpression(equiJoinClause));
            }
            joinNode.getFilter().ifPresent(builder::add);
            return logicalRowExpressions.combineConjuncts(builder.build());
        }

        private RowExpression toRowExpression(EquiJoinClause equiJoinClause)
        {
            return buildEqualsExpression(functionAndTypeManager, equiJoinClause.getLeft(), equiJoinClause.getRight());
        }

        private JoinNode tryNormalizeToOuterToInnerJoin(JoinNode node, RowExpression inheritedPredicate)
        {
            checkArgument(EnumSet.of(INNER, RIGHT, LEFT, FULL).contains(node.getType()), "Unsupported join type: %s", node.getType());

            if (node.getType() == JoinType.INNER) {
                return node;
            }

            if (node.getType() == JoinType.FULL) {
                boolean canConvertToLeftJoin = canConvertOuterToInner(node.getLeft().getOutputVariables(), inheritedPredicate);
                boolean canConvertToRightJoin = canConvertOuterToInner(node.getRight().getOutputVariables(), inheritedPredicate);
                if (!canConvertToLeftJoin && !canConvertToRightJoin) {
                    return node;
                }
                if (canConvertToLeftJoin && canConvertToRightJoin) {
                    return new JoinNode(
                            node.getSourceLocation(),
                            node.getId(),
                            INNER,
                            node.getLeft(),
                            node.getRight(),
                            node.getCriteria(),
                            node.getOutputVariables(),
                            node.getFilter(),
                            node.getLeftHashVariable(),
                            node.getRightHashVariable(),
                            node.getDistributionType(),
                            node.getDynamicFilters());
                }
                else {
                    return new JoinNode(
                            node.getSourceLocation(),
                            node.getId(),
                            canConvertToLeftJoin ? LEFT : RIGHT,
                            node.getLeft(),
                            node.getRight(),
                            node.getCriteria(),
                            node.getOutputVariables(),
                            node.getFilter(),
                            node.getLeftHashVariable(),
                            node.getRightHashVariable(),
                            node.getDistributionType(),
                            node.getDynamicFilters());
                }
            }

            if (node.getType() == JoinType.LEFT && !canConvertOuterToInner(node.getRight().getOutputVariables(), inheritedPredicate) ||
                    node.getType() == JoinType.RIGHT && !canConvertOuterToInner(node.getLeft().getOutputVariables(), inheritedPredicate)) {
                return node;
            }
            return new JoinNode(
                    node.getSourceLocation(),
                    node.getId(),
                    JoinType.INNER,
                    node.getLeft(),
                    node.getRight(),
                    node.getCriteria(),
                    node.getOutputVariables(),
                    node.getFilter(),
                    node.getLeftHashVariable(),
                    node.getRightHashVariable(),
                    node.getDistributionType(),
                    node.getDynamicFilters());
        }

        private boolean canConvertOuterToInner(List<VariableReferenceExpression> innerVariablesForOuterJoin, RowExpression inheritedPredicate)
        {
            Set<VariableReferenceExpression> innerVariables = ImmutableSet.copyOf(innerVariablesForOuterJoin);
            for (RowExpression conjunct : extractConjuncts(inheritedPredicate)) {
                if (determinismEvaluator.isDeterministic(conjunct)) {
                    // Ignore a conjunct for this test if we can not deterministically get responses from it
                    RowExpression response = nullInputEvaluator(innerVariables, conjunct);
                    if (response == null || Expressions.isNull(response) || FALSE_CONSTANT.equals(response)) {
                        // If there is a single conjunct that returns FALSE or NULL given all NULL inputs for the inner side symbols of an outer join
                        // then this conjunct removes all effects of the outer join, and effectively turns this into an equivalent of an inner join.
                        // So, let's just rewrite this join as an INNER join
                        return true;
                    }
                }
            }
            return false;
        }

        // Temporary implementation for joins because the SimplifyExpressions optimizers can not run properly on join clauses
        private RowExpression simplifyExpression(RowExpression expression)
        {
            return expressionOptimizerProvider.getExpressionOptimizer(session.toConnectorSession()).optimize(expression, ExpressionOptimizer.Level.SERIALIZABLE, session.toConnectorSession());
        }

        private boolean areExpressionsEquivalent(RowExpression leftExpression, RowExpression rightExpression)
        {
            return expressionEquivalence.areExpressionsEquivalent(simplifyExpression(leftExpression), simplifyExpression(rightExpression));
        }

        /**
         * Evaluates an expression's response to binding the specified input symbols to NULL
         */
        private RowExpression nullInputEvaluator(final Collection<VariableReferenceExpression> nullSymbols, RowExpression expression)
        {
            expression = RowExpressionNodeInliner.replaceExpression(expression, nullSymbols.stream()
                    .collect(Collectors.toMap(identity(), variable -> constantNull(variable.getSourceLocation(), variable.getType()))));
            return expressionOptimizerProvider.getExpressionOptimizer(session.toConnectorSession()).optimize(expression, ExpressionOptimizer.Level.OPTIMIZED, session.toConnectorSession());
        }

        private Predicate<RowExpression> joinEqualityExpression(final Collection<VariableReferenceExpression> leftVariables)
        {
            return expression -> {
                // At this point in time, our join predicates need to be deterministic
                if (determinismEvaluator.isDeterministic(expression) && isOperation(expression, EQUAL)) {
                    Set<VariableReferenceExpression> variables1 = extractUnique(getLeft(expression));
                    Set<VariableReferenceExpression> variables2 = extractUnique(getRight(expression));
                    if (variables1.isEmpty() || variables2.isEmpty()) {
                        return false;
                    }
                    return (Iterables.all(variables1, in(leftVariables)) && Iterables.all(variables2, not(in(leftVariables)))) ||
                            (Iterables.all(variables2, in(leftVariables)) && Iterables.all(variables1, not(in(leftVariables))));
                }
                return false;
            };
        }

        private boolean isOperation(RowExpression expression, OperatorType type)
        {
            if (expression instanceof CallExpression) {
                Optional<OperatorType> operatorType = functionAndTypeManager.getFunctionMetadata(((CallExpression) expression).getFunctionHandle()).getOperatorType();
                if (operatorType.isPresent()) {
                    return operatorType.get().equals(type);
                }
            }
            return false;
        }

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            Set<RowExpression> inheritedConjuncts = ImmutableSet.copyOf(extractConjuncts(context.get()));
            if (inheritedConjuncts.contains(node.getSemiJoinOutput()) ||
                    inheritedConjuncts.contains(logicalRowExpressions.equalsCallExpression(node.getSemiJoinOutput(), TRUE_CONSTANT)) ||
                    inheritedConjuncts.contains(logicalRowExpressions.equalsCallExpression(TRUE_CONSTANT, node.getSemiJoinOutput()))) {
                return visitFilteringSemiJoin(node, context);
            }
            return visitNonFilteringSemiJoin(node, context);
        }

        private PlanNode visitNonFilteringSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();
            List<RowExpression> sourceConjuncts = new ArrayList<>();
            List<RowExpression> postJoinConjuncts = new ArrayList<>();

            // TODO: see if there are predicates that can be inferred from the semi join output

            PlanNode rewrittenFilteringSource = context.defaultRewrite(node.getFilteringSource(), TRUE_CONSTANT);

            // Push inheritedPredicates down to the source if they don't involve the semi join output
            EqualityInference inheritedInference = new EqualityInference.Builder(functionAndTypeManager)
                    .addEqualityInference(inheritedPredicate)
                    .build();
            for (RowExpression conjunct : new EqualityInference.Builder(functionAndTypeManager).nonInferableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = inheritedInference.rewriteExpressionAllowNonDeterministic(conjunct, in(node.getSource().getOutputVariables()));
                // Since each source row is reflected exactly once in the output, ok to push non-deterministic predicates down
                if (rewrittenConjunct != null) {
                    sourceConjuncts.add(rewrittenConjunct);
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // Add the inherited equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(in(node.getSource()
                    .getOutputVariables())::apply);
            sourceConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), logicalRowExpressions.combineConjuncts(sourceConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource() || rewrittenFilteringSource != node.getFilteringSource()) {
                planChanged = true;
                output = new SemiJoinNode(node.getSourceLocation(), node.getId(), rewrittenSource, rewrittenFilteringSource, node.getSourceJoinVariable(), node.getFilteringSourceJoinVariable(), node.getSemiJoinOutput(), node.getSourceHashVariable(), node.getFilteringSourceHashVariable(), node.getDistributionType(), node.getDynamicFilters());
            }
            if (!postJoinConjuncts.isEmpty()) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, logicalRowExpressions.combineConjuncts(postJoinConjuncts));
            }
            return output;
        }

        private boolean isEnableDynamicFiltering()
        {
            return !nativeExecution && SystemSessionProperties.isEnableDynamicFiltering(session);
        }

        private PlanNode visitFilteringSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            List<RowExpression> postJoinConjuncts = new ArrayList<>();
            List<RowExpression> sourceConjuncts = new ArrayList<>();
            List<RowExpression> filteringSourceConjuncts = new ArrayList<>();

            // Remove any conjuncts which involve the semi join output from the passed in predicate, these cannot be pushed down or rewritten
            ImmutableList.Builder<RowExpression> predicateOnSources = ImmutableList.builder();
            LogicalRowExpressions.extractConjuncts(context.get()).forEach(conjunct -> {
                if (extractUnique(conjunct).contains(node.getSemiJoinOutput())) {
                    postJoinConjuncts.add(conjunct);
                }
                else {
                    predicateOnSources.add(conjunct);
                }
            });

            RowExpression inheritedPredicate = logicalRowExpressions.combineConjuncts(predicateOnSources.build());
            RowExpression deterministicInheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);
            RowExpression sourceEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(effectivePredicateExtractor.extract(node.getSource()));
            RowExpression filteringSourceEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(effectivePredicateExtractor.extract(node.getFilteringSource()));
            RowExpression joinExpression = buildEqualsExpression(functionAndTypeManager, node.getSourceJoinVariable(), node.getFilteringSourceJoinVariable());

            List<VariableReferenceExpression> sourceVariables = node.getSource().getOutputVariables();
            List<VariableReferenceExpression> filteringSourceVariables = node.getFilteringSource().getOutputVariables();

            // Generate equality inferences
            EqualityInference allInference = createEqualityInference(deterministicInheritedPredicate, sourceEffectivePredicate, filteringSourceEffectivePredicate, joinExpression);
            EqualityInference allInferenceWithoutSourceInferred = createEqualityInference(deterministicInheritedPredicate, filteringSourceEffectivePredicate, joinExpression);
            EqualityInference allInferenceWithoutFilteringSourceInferred = createEqualityInference(deterministicInheritedPredicate, sourceEffectivePredicate, joinExpression);

            // Push inherited Predicates down to the source if possible
            for (RowExpression conjunct : nonInferableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = allInference.rewriteExpressionAllowNonDeterministic(conjunct, in(sourceVariables));
                // Since each source row is reflected exactly once in the output, ok to push non-deterministic predicates down
                if (rewrittenConjunct != null) {
                    sourceConjuncts.add(rewrittenConjunct);
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // Push inherited Predicates down to the filtering source if possible
            for (RowExpression conjunct : nonInferableConjuncts(deterministicInheritedPredicate)) {
                RowExpression rewrittenConjunct = allInference.rewriteExpression(conjunct, in(filteringSourceVariables));
                // We cannot push non-deterministic predicates to filtering side. Each filtering side row have to be
                // logically reevaluated for each source row.
                if (rewrittenConjunct != null) {
                    filteringSourceConjuncts.add(rewrittenConjunct);
                }
            }

            // move effective predicate conjuncts source <-> filter
            // See if we can push the filtering source effective predicate to the source side
            for (RowExpression conjunct : nonInferableConjuncts(filteringSourceEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(sourceVariables));
                if (rewritten != null) {
                    sourceConjuncts.add(rewritten);
                }
            }

            // See if we can push the source effective predicate to the filtering source side
            for (RowExpression conjunct : nonInferableConjuncts(sourceEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(filteringSourceVariables));
                if (rewritten != null) {
                    filteringSourceConjuncts.add(rewritten);
                }
            }

            // Add equalities from the inference back in
            sourceConjuncts.addAll(allInferenceWithoutSourceInferred.generateEqualitiesPartitionedBy(in(sourceVariables)).getScopeEqualities());
            filteringSourceConjuncts.addAll(allInferenceWithoutFilteringSourceInferred.generateEqualitiesPartitionedBy(in(filteringSourceVariables)).getScopeEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), logicalRowExpressions.combineConjuncts(sourceConjuncts));
            PlanNode rewrittenFilteringSource = context.rewrite(node.getFilteringSource(), logicalRowExpressions.combineConjuncts(filteringSourceConjuncts));

            Map<String, VariableReferenceExpression> dynamicFilters = ImmutableMap.of();
            if (isEnableDynamicFiltering()) {
                DynamicFiltersResult dynamicFiltersResult = createDynamicFilters(node.getSourceJoinVariable(), node.getFilteringSourceJoinVariable(), idAllocator, metadata.getFunctionAndTypeManager());
                dynamicFilters = dynamicFiltersResult.getDynamicFilters();
                // add filter node on top of probe
                rewrittenSource = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), rewrittenSource, logicalRowExpressions.combineConjuncts(dynamicFiltersResult.getPredicates()));
            }

            PlanNode output = node;
            if (rewrittenSource != node.getSource() || rewrittenFilteringSource != node.getFilteringSource() || !dynamicFilters.isEmpty()) {
                planChanged = true;
                output = new SemiJoinNode(
                        node.getSourceLocation(),
                        node.getId(),
                        rewrittenSource,
                        rewrittenFilteringSource,
                        node.getSourceJoinVariable(),
                        node.getFilteringSourceJoinVariable(),
                        node.getSemiJoinOutput(),
                        node.getSourceHashVariable(),
                        node.getFilteringSourceHashVariable(),
                        node.getDistributionType(),
                        dynamicFilters);
            }
            if (!postJoinConjuncts.isEmpty()) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, logicalRowExpressions.combineConjuncts(postJoinConjuncts));
            }
            return output;
        }

        private Iterable<RowExpression> nonInferableConjuncts(RowExpression inheritedPredicate)
        {
            return new EqualityInference.Builder(functionAndTypeManager)
                    .nonInferableConjuncts(inheritedPredicate);
        }

        private EqualityInference createEqualityInference(RowExpression... expressions)
        {
            return new EqualityInference.Builder(functionAndTypeManager)
                    .addEqualityInference(expressions)
                    .build();
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<RowExpression> context)
        {
            if (node.hasEmptyGroupingSet()) {
                // TODO: in case of grouping sets, we should be able to push the filters over grouping keys below the aggregation
                // and also preserve the filter above the aggregation if it has an empty grouping set
                return visitPlan(node, context);
            }

            RowExpression inheritedPredicate = context.get();

            EqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<RowExpression> pushdownConjuncts = new ArrayList<>();
            List<RowExpression> postAggregationConjuncts = new ArrayList<>();

            List<VariableReferenceExpression> groupingKeyVariables = node.getGroupingKeys();

            // Strip out non-deterministic conjuncts
            postAggregationConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic))));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (RowExpression conjunct : nonInferableConjuncts(inheritedPredicate)) {
                if (node.getGroupIdVariable().isPresent() && extractUnique(conjunct).contains(node.getGroupIdVariable().get())) {
                    // aggregation operator synthesizes outputs for group ids corresponding to the global grouping set (i.e., ()), so we
                    // need to preserve any predicates that evaluate the group id to run after the aggregation
                    // TODO: we should be able to infer if conditions on grouping() correspond to global grouping sets to determine whether
                    // we need to do this for each specific case
                    postAggregationConjuncts.add(conjunct);
                    continue;
                }

                RowExpression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(groupingKeyVariables));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postAggregationConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(groupingKeyVariables)::apply);
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), logicalRowExpressions.combineConjuncts(pushdownConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource()) {
                planChanged = true;
                output = new AggregationNode(
                        node.getSourceLocation(),
                        node.getId(),
                        rewrittenSource,
                        node.getAggregations(),
                        node.getGroupingSets(),
                        ImmutableList.of(),
                        node.getStep(),
                        node.getHashVariable(),
                        node.getGroupIdVariable(),
                        node.getAggregationId());
            }
            if (!postAggregationConjuncts.isEmpty()) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, logicalRowExpressions.combineConjuncts(postAggregationConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            EqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<RowExpression> pushdownConjuncts = new ArrayList<>();
            List<RowExpression> postUnnestConjuncts = new ArrayList<>();

            // Strip out non-deterministic conjuncts
            postUnnestConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic))));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (RowExpression conjunct : nonInferableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(node.getReplicateVariables()));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postUnnestConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(node.getReplicateVariables())::apply);
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), logicalRowExpressions.combineConjuncts(pushdownConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource()) {
                planChanged = true;
                output = new UnnestNode(node.getSourceLocation(), node.getId(), rewrittenSource, node.getReplicateVariables(), node.getUnnestVariables(), node.getOrdinalityVariable());
            }
            if (!postUnnestConjuncts.isEmpty()) {
                planChanged = true;
                output = new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), output, logicalRowExpressions.combineConjuncts(postUnnestConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitSample(SampleNode node, RewriteContext<RowExpression> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<RowExpression> context)
        {
            RowExpression predicate = simplifyExpression(context.get());

            if (!TRUE_CONSTANT.equals(predicate)) {
                planChanged = true;
                return new FilterNode(node.getSourceLocation(), idAllocator.getNextId(), node, predicate);
            }

            return node;
        }

        @Override
        public PlanNode visitAssignUniqueId(AssignUniqueId node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> predicateVariables = extractUnique(context.get());
            checkState(!predicateVariables.contains(node.getIdVariable()), "UniqueId in predicate is not yet supported");
            return context.defaultRewrite(node, context.get());
        }

        private static CallExpression buildEqualsExpression(FunctionAndTypeManager functionAndTypeManager, RowExpression left, RowExpression right)
        {
            return call(
                    EQUAL.getFunctionName().getObjectName(),
                    functionAndTypeManager.resolveOperator(EQUAL, fromTypes(left.getType(), right.getType())),
                    BOOLEAN,
                    left,
                    right);
        }
    }
}
