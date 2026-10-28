"""GNSS trust model: helper behaviour and training sanity on synthetic fixes."""
from __future__ import annotations

import unittest

import numpy as np

from training import gnss_trust as gt


class HelperTest(unittest.TestCase):
    def test_spearman_is_one_for_monotone_and_minus_one_for_reversed(self):
        a = np.array([1.0, 5.0, 2.0, 9.0, 3.0])
        self.assertAlmostEqual(gt.spearman(a, a ** 3), 1.0)
        self.assertAlmostEqual(gt.spearman(a, -a), -1.0)


class TrainingTest(unittest.TestCase):
    def data(self, n=600, seed=0):
        rng = np.random.default_rng(seed)
        x = np.zeros((n, len(gt.FEATURES)))
        x[:, 0] = rng.uniform(2, 12, n)                    # reported accuracy, uninformative here
        x[:, 1] = np.log(x[:, 0])
        x[:, 2] = rng.integers(4, 20, n)
        x[:, 3] = rng.uniform(0, 30, n)
        x[:, 4] = rng.uniform(1, 12, n)
        x[:, 5] = rng.uniform(0, 8, n)                     # speed consistency drives the real error
        x[:, 6] = rng.uniform(0, 40, n)
        x[:, 7] = 1.0
        err = 1.0 + 1.5 * x[:, 5] + rng.normal(0, 0.3, n).clip(-0.9)
        return x, np.maximum(err, 0.0)

    def test_model_predicts_nonnegative_errors_and_beats_the_reported_accuracy_ordering(self):
        x, err = self.data()
        model = gt.train(x[:400], err[:400], hidden=8, epochs=120)
        pred = gt.predict_error_m(model, x[400:])
        self.assertTrue(np.all(pred >= 0.0))
        self.assertGreater(gt.spearman(pred, err[400:]), 0.8)
        self.assertLess(abs(gt.spearman(x[400:, 0], err[400:])), 0.3)

    def test_training_is_deterministic(self):
        x, err = self.data(200)
        a = gt.predict_error_m(gt.train(x, err, hidden=4, epochs=5), x)
        b = gt.predict_error_m(gt.train(x, err, hidden=4, epochs=5), x)
        np.testing.assert_array_equal(a, b)


if __name__ == "__main__":
    unittest.main()
