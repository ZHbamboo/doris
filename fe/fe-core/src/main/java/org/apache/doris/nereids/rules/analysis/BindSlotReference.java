// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.analyzer.UnboundAlias;
import org.apache.doris.nereids.analyzer.UnboundSlot;
import org.apache.doris.nereids.analyzer.UnboundStar;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.properties.OrderKey;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.visitor.DefaultSubExprRewriter;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalSort;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * BindSlotReference.
 */
public class BindSlotReference implements AnalysisRuleFactory {
    private final Optional<Scope> outerScope;

    public BindSlotReference() {
        this(Optional.empty());
    }

    public BindSlotReference(Optional<Scope> outputScope) {
        this.outerScope = Objects.requireNonNull(outputScope, "outerScope can not be null");
    }

    private Scope toScope(List<Slot> slots) {
        if (outerScope.isPresent()) {
            return new Scope(outerScope, slots);
        } else {
            return new Scope(slots);
        }
    }

    @Override
    public List<Rule> buildRules() {
        return ImmutableList.of(
            RuleType.BINDING_PROJECT_SLOT.build(
                logicalProject().thenApply(ctx -> {
                    LogicalProject<GroupPlan> project = ctx.root;
                    List<NamedExpression> boundSlots =
                            bind(project.getProjects(), project.children(), project, ctx.cascadesContext);
                    return new LogicalProject<>(flatBoundStar(boundSlots), project.child());
                })
            ),
            RuleType.BINDING_FILTER_SLOT.build(
                logicalFilter().thenApply(ctx -> {
                    LogicalFilter<GroupPlan> filter = ctx.root;
                    Expression boundPredicates = bind(filter.getPredicates(), filter.children(),
                            filter, ctx.cascadesContext);
                    return new LogicalFilter<>(boundPredicates, filter.child());
                })
            ),
            RuleType.BINDING_JOIN_SLOT.build(
                logicalJoin().thenApply(ctx -> {
                    LogicalJoin<GroupPlan, GroupPlan> join = ctx.root;
                    Optional<Expression> cond = join.getCondition()
                            .map(expr -> bind(expr, join.children(), join, ctx.cascadesContext));
                    return new LogicalJoin<>(join.getJoinType(), cond, join.left(), join.right());
                })
            ),
            RuleType.BINDING_AGGREGATE_SLOT.build(
                logicalAggregate().thenApply(ctx -> {
                    LogicalAggregate<GroupPlan> agg = ctx.root;
                    List<Expression> groupBy =
                            bind(agg.getGroupByExpressions(), agg.children(), agg, ctx.cascadesContext);
                    List<NamedExpression> output =
                            bind(agg.getOutputExpressions(), agg.children(), agg, ctx.cascadesContext);
                    return agg.withGroupByAndOutput(groupBy, output);
                })
            ),
            RuleType.BINDING_SORT_SLOT.build(
                logicalSort().thenApply(ctx -> {
                    LogicalSort<GroupPlan> sort = ctx.root;
                    List<OrderKey> sortItemList = sort.getOrderKeys()
                            .stream()
                            .map(orderKey -> {
                                Expression item = bind(orderKey.getExpr(), sort.children(), sort, ctx.cascadesContext);
                                return new OrderKey(item, orderKey.isAsc(), orderKey.isNullFirst());
                            }).collect(Collectors.toList());

                    return new LogicalSort<>(sortItemList, sort.child());
                })
            ),

            // this rewrite is necessary because we should replace the logicalProperties which refer the child
            // unboundLogicalProperties to a new LogicalProperties. This restriction is because we move the
            // analysis stage after build the memo, and cause parent's plan can not update logical properties
            // when the children are changed. we should discuss later and refactor it.
            RuleType.BINDING_SUBQUERY_ALIAS_SLOT.build(
                logicalSubQueryAlias().then(alias -> alias.withChildren(ImmutableList.of(alias.child())))
            ),
            RuleType.BINDING_LIMIT_SLOT.build(
                logicalLimit().then(limit -> limit.withChildren(ImmutableList.of(limit.child())))
            )
        );
    }

    private List<NamedExpression> flatBoundStar(List<NamedExpression> boundSlots) {
        return boundSlots
            .stream()
            .flatMap(slot -> {
                if (slot instanceof BoundStar) {
                    return ((BoundStar) slot).getSlots().stream();
                } else {
                    return Stream.of(slot);
                }
            }).collect(Collectors.toList());
    }

    private <E extends Expression> List<E> bind(List<E> exprList, List<Plan> inputs, Plan plan,
            CascadesContext cascadesContext) {
        return exprList.stream()
            .map(expr -> bind(expr, inputs, plan, cascadesContext))
            .collect(Collectors.toList());
    }

    private <E extends Expression> E bind(E expr, List<Plan> inputs, Plan plan, CascadesContext cascadesContext) {
        List<Slot> boundedSlots = inputs.stream()
                .flatMap(input -> input.getOutput().stream())
                .collect(Collectors.toList());
        return (E) new SlotBinder(toScope(boundedSlots), plan, cascadesContext).bind(expr);
    }

    private class SlotBinder extends DefaultSubExprRewriter<Void> {
        private final Plan plan;

        public SlotBinder(Scope scope, Plan plan, CascadesContext cascadesContext) {
            super(scope, cascadesContext);
            this.plan = plan;
        }

        public Expression bind(Expression expression) {
            return expression.accept(this, null);
        }

        @Override
        public Expression visitUnboundAlias(UnboundAlias unboundAlias, Void context) {
            Expression child = unboundAlias.child().accept(this, context);
            if (child instanceof NamedExpression) {
                return new Alias(child, ((NamedExpression) child).getName());
            } else {
                // TODO: resolve aliases
                return new Alias(child, child.toSql());
            }
        }

        @Override
        public Slot visitUnboundSlot(UnboundSlot unboundSlot, Void context) {
            Optional<List<Slot>> boundedOpt = getScope()
                    .toScopeLink() // Scope Link from inner scope to outer scope
                    .stream()
                    .map(scope -> bindSlot(unboundSlot, scope.getSlots()))
                    .filter(slots -> !slots.isEmpty())
                    .findFirst();
            if (!boundedOpt.isPresent()) {
                throw new AnalysisException("Cannot resolve " + unboundSlot.toString());
            }
            List<Slot> bounded = boundedOpt.get();
            switch (bounded.size()) {
                case 1:
                    return bounded.get(0);
                default:
                    throw new AnalysisException(unboundSlot + " is ambiguous： "
                            + bounded.stream()
                            .map(Slot::toString)
                            .collect(Collectors.joining(", ")));
            }
        }

        @Override
        public Expression visitUnboundStar(UnboundStar unboundStar, Void context) {
            if (!(plan instanceof LogicalProject)) {
                throw new AnalysisException("UnboundStar must exists in Projection");
            }
            List<String> qualifier = unboundStar.getQualifier();
            switch (qualifier.size()) {
                case 0: // select *
                    return new BoundStar(getScope().getSlots());
                case 1: // select table.*
                case 2: // select db.table.*
                    return bindQualifiedStar(qualifier, context);
                default:
                    throw new AnalysisException("Not supported qualifier: "
                        + StringUtils.join(qualifier, "."));
            }
        }

        private BoundStar bindQualifiedStar(List<String> qualifierStar, Void context) {
            // FIXME: compatible with previous behavior:
            // https://github.com/apache/doris/pull/10415/files/3fe9cb0c3f805ab3a9678033b281b16ad93ec60a#r910239452
            List<Slot> slots = getScope().getSlots().stream().filter(boundSlot -> {
                switch (qualifierStar.size()) {
                    // table.*
                    case 1:
                        List<String> boundSlotQualifier = boundSlot.getQualifier();
                        switch (boundSlotQualifier.size()) {
                            // bound slot is `column` and no qualified
                            case 0: return false;
                            case 1: // bound slot is `table`.`column`
                                return qualifierStar.get(0).equalsIgnoreCase(boundSlotQualifier.get(0));
                            case 2:// bound slot is `db`.`table`.`column`
                                return qualifierStar.get(0).equalsIgnoreCase(boundSlotQualifier.get(1));
                            default:
                                throw new AnalysisException("Not supported qualifier: "
                                    + StringUtils.join(qualifierStar, "."));
                        }
                    case 2: // db.table.*
                        boundSlotQualifier = boundSlot.getQualifier();
                        switch (boundSlotQualifier.size()) {
                            // bound slot is `column` and no qualified
                            case 0:
                            case 1: // bound slot is `table`.`column`
                                return false;
                            case 2:// bound slot is `db`.`table`.`column`
                                return qualifierStar.get(0).equalsIgnoreCase(boundSlotQualifier.get(0))
                                        && qualifierStar.get(1).equalsIgnoreCase(boundSlotQualifier.get(1));
                            default:
                                throw new AnalysisException("Not supported qualifier: "
                                    + StringUtils.join(qualifierStar, ".") + ".*");
                        }
                    default:
                        throw new AnalysisException("Not supported name: "
                            + StringUtils.join(qualifierStar, ".") + ".*");
                }
            }).collect(Collectors.toList());

            return new BoundStar(slots);
        }

        private List<Slot> bindSlot(UnboundSlot unboundSlot, List<Slot> boundSlots) {
            return boundSlots.stream().filter(boundSlot -> {
                List<String> nameParts = unboundSlot.getNameParts();
                switch (nameParts.size()) {
                    case 1:
                        // Unbound slot name is `column`
                        return nameParts.get(0).equalsIgnoreCase(boundSlot.getName());
                    case 2:
                        // Unbound slot name is `table`.`column`
                        List<String> qualifier = boundSlot.getQualifier();
                        String name = boundSlot.getName();
                        switch (qualifier.size()) {
                            case 2:
                                // qualifier is `db`.`table`
                                return nameParts.get(0).equalsIgnoreCase(qualifier.get(1))
                                        && nameParts.get(1).equalsIgnoreCase(name);
                            case 1:
                                // qualifier is `table`
                                return nameParts.get(0).equalsIgnoreCase(qualifier.get(0))
                                        && nameParts.get(1).equalsIgnoreCase(name);
                            case 0:
                                // has no qualifiers
                                return nameParts.get(1).equalsIgnoreCase(name);
                            default:
                                throw new AnalysisException("Not supported qualifier: "
                                        + StringUtils.join(qualifier, "."));
                        }
                    default:
                        throw new AnalysisException("Not supported name: "
                            + StringUtils.join(nameParts, "."));
                }
            }).collect(Collectors.toList());
        }
    }

    /** BoundStar is used to wrap list of slots for temporary. */
    private class BoundStar extends NamedExpression {
        public BoundStar(List<Slot> children) {
            super(children.toArray(new Slot[0]));
            Preconditions.checkArgument(children.stream().noneMatch(slot -> slot instanceof UnboundSlot),
                    "BoundStar can not wrap UnboundSlot"
            );
        }

        public String toSql() {
            return children.stream().map(Expression::toSql).collect(Collectors.joining(", "));
        }

        public List<Slot> getSlots() {
            return (List) children();
        }
    }
}
