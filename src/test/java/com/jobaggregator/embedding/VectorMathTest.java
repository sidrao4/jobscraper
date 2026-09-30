package com.jobaggregator.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class VectorMathTest {

    @Test
    void cosineOfParallelOrthogonalAndOpposite() {
        assertThat(VectorMath.cosine(new float[] {1, 2, 3}, new float[] {2, 4, 6})).isCloseTo(1.0, within(1e-6));
        assertThat(VectorMath.cosine(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(0.0, within(1e-6));
        assertThat(VectorMath.cosine(new float[] {1, 1}, new float[] {-1, -1})).isCloseTo(-1.0, within(1e-6));
    }

    @Test
    void zeroVectorScoresZero() {
        assertThat(VectorMath.cosine(new float[] {0, 0}, new float[] {1, 1})).isZero();
    }
}
