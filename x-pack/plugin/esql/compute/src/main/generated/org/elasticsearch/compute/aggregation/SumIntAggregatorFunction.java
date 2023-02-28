// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link AggregatorFunction} implementation for {@link SumIntAggregator}.
 * This class is generated. Do not edit it.
 */
public final class SumIntAggregatorFunction implements AggregatorFunction {
  private final LongState state;

  private final int channel;

  public SumIntAggregatorFunction(int channel, LongState state) {
    this.channel = channel;
    this.state = state;
  }

  public static SumIntAggregatorFunction create(int channel) {
    return new SumIntAggregatorFunction(channel, new LongState(SumIntAggregator.init()));
  }

  @Override
  public void addRawInput(Page page) {
    assert channel >= 0;
    ElementType type = page.getBlock(channel).elementType();
    if (type == ElementType.NULL) {
      return;
    }
    IntBlock block = page.getBlock(channel);
    IntVector vector = block.asVector();
    if (vector != null) {
      addRawVector(vector);
    } else {
      addRawBlock(block);
    }
  }

  private void addRawVector(IntVector vector) {
    for (int i = 0; i < vector.getPositionCount(); i++) {
      state.longValue(SumIntAggregator.combine(state.longValue(), vector.getInt(i)));
    }
  }

  private void addRawBlock(IntBlock block) {
    for (int i = 0; i < block.getTotalValueCount(); i++) {
      if (block.isNull(i) == false) {
        state.longValue(SumIntAggregator.combine(state.longValue(), block.getInt(i)));
      }
    }
  }

  @Override
  public void addIntermediateInput(Block block) {
    assert channel == -1;
    Vector vector = block.asVector();
    if (vector == null || vector instanceof AggregatorStateVector == false) {
      throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
    }
    @SuppressWarnings("unchecked") AggregatorStateVector<LongState> blobVector = (AggregatorStateVector<LongState>) vector;
    LongState tmpState = new LongState();
    for (int i = 0; i < block.getPositionCount(); i++) {
      blobVector.get(i, tmpState);
      SumIntAggregator.combineStates(state, tmpState);
    }
  }

  @Override
  public Block evaluateIntermediate() {
    AggregatorStateVector.Builder<AggregatorStateVector<LongState>, LongState> builder =
        AggregatorStateVector.builderOfAggregatorState(LongState.class, state.getEstimatedSize());
    builder.add(state, IntVector.range(0, 1));
    return builder.build().asBlock();
  }

  @Override
  public Block evaluateFinal() {
    return LongBlock.newConstantBlockWith(state.longValue(), 1);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName()).append("[");
    sb.append("channel=").append(channel);
    sb.append("]");
    return sb.toString();
  }
}
