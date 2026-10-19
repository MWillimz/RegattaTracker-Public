package de.williserv.regattaclient

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.linear.SingularValueDecomposition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// Port of RegattaLink CLI _solve_ellipsoid + fit_archive. Work in double precision.
internal object RegattaLinkMagFitter {
    private fun median(a: DoubleArray): Double {
        require(a.isNotEmpty())
        val sorted = a.sorted()
        return if (sorted.size % 2 != 0) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it })

    private data class Solution(val offset: DoubleArray, val a: Array2DRowRealMatrix)

    private fun solve(points: List<DoubleArray>): Solution {
        val origin = DoubleArray(3) { col -> points.map { it[col] }.average() }
        val scale = median(points.map { p ->
            norm(DoubleArray(3) { p[it] - origin[it] })
        }.toDoubleArray())
        require(scale >= 5.0 && scale.isFinite()) { "MAG capture span too small" }
        val design = Array2DRowRealMatrix(points.size, 9)
        points.forEachIndexed { row, p ->
            val x = (p[0] - origin[0]) / scale
            val y = (p[1] - origin[1]) / scale
            val z = (p[2] - origin[2]) / scale
            arrayOf(x*x, y*y, z*z, 2*x*y, 2*x*z, 2*y*z, x, y, z)
                .forEachIndexed { col, value -> design.setEntry(row, col, value) }
        }
        val svd = SingularValueDecomposition(design)
        val singular = svd.singularValues
        require(svd.rank == 9 && singular.last() > 0.0 &&
            singular.first() / singular.last() <= 1e5) { "MAG ellipsoid ill-conditioned" }
        val c = svd.solver.solve(ArrayRealVector(DoubleArray(points.size) { 1.0 })).toArray()
        val q = Array2DRowRealMatrix(arrayOf(
            doubleArrayOf(c[0], c[3], c[4]),
            doubleArrayOf(c[3], c[1], c[5]),
            doubleArrayOf(c[4], c[5], c[2])
        ))
        require(EigenDecomposition(q).realEigenvalues.all { it > 0.0 && it.isFinite() }) {
            "MAG ellipsoid is not positive definite"
        }
        val center = SingularValueDecomposition(q).solver.solve(
            ArrayRealVector(doubleArrayOf(-c[6]/2, -c[7]/2, -c[8]/2))
        ).toArray()
        val denominator = 1.0 + center.indices.sumOf { i ->
            center[i] * center.indices.sumOf { j -> q.getEntry(i, j) * center[j] }
        }
        require(denominator > 0.0 && denominator.isFinite()) { "Invalid MAG radius" }
        val shape = q.scalarMultiply(1.0 / denominator)
        val eig = EigenDecomposition(shape)
        require(eig.realEigenvalues.all { it > 0.0 && it.isFinite() }) {
            "MAG correction is not positive definite"
        }
        val a = Array2DRowRealMatrix(3, 3)
        repeat(3) { k ->
            val e = sqrt(eig.getRealEigenvalue(k))
            val v = eig.getEigenvector(k).toArray()
            repeat(3) { i -> repeat(3) { j ->
                a.addToEntry(i, j, e * v[i] * v[j])
            } }
        }
        val det = org.apache.commons.math3.linear.LUDecomposition(a).determinant
        require(det > 0.0 && det.isFinite()) { "Invalid MAG matrix determinant" }
        val normal = a.scalarMultiply(1.0 / det.pow(1.0/3.0))
        return Solution(DoubleArray(3) { origin[it] + scale * center[it] },
            normal as Array2DRowRealMatrix)
    }

    private fun corrected(p: DoubleArray, s: Solution): DoubleArray {
        val translated = ArrayRealVector(DoubleArray(3) { p[it] - s.offset[it] })
        return s.a.operate(translated).toArray()
    }

    fun fit(archive: RegattaLinkMagArchive): RegattaLinkMagFit {
        val points = archive.sectors.mapNotNull { it.median }
        require(points.size >= 36) { "Collect >=36 occupied sectors for 3D" }
        var mask = BooleanArray(points.size) { true }
        repeat(4) {
            val inliers = points.indices.filter { mask[it] }
            require(inliers.size >= 36) { "Fewer than 36 sectors after filtering" }
            val solution = solve(inliers.map { points[it] })
            val radii = points.map { norm(corrected(it, solution)) }.toDoubleArray()
            val radius = median(inliers.map { radii[it] }.toDoubleArray())
            val residuals = radii.map { abs(it - radius) }.toDoubleArray()
            val med = median(inliers.map { residuals[it] }.toDoubleArray())
            val mad = median(inliers.map { abs(residuals[it] - med) }.toDoubleArray())
            val threshold = max(radius * 0.065, med + 3.5 * 1.4826 * mad)
            val newMask = BooleanArray(points.size) { residuals[it] <= threshold }
            require(newMask.count { it } >= max(36, (0.8 * points.size).toInt())) {
                "Mixed magnetic field / too many outlier sectors"
            }
            if (newMask.contentEquals(mask)) return@repeat
            mask = newMask
        }
        val inliers = points.indices.filter { mask[it] }
        val solution = solve(inliers.map { points[it] })
        val corrected = inliers.map { corrected(points[it], solution) }
        val radii = corrected.map(::norm).toDoubleArray()
        val radius = median(radii)
        require(radius in 10.0..100.0) { "Magnetic field outside 10-100 uT" }
        val octants = corrected.map { v ->
            (if (v[0] >= 0) 1 else 0) or
                (if (v[1] >= 0) 2 else 0) or
                (if (v[2] >= 0) 4 else 0)
        }.toSet().size
        require(octants >= 7) { "3D calibration requires >=7 spatial octants" }
        val directions = corrected.map { v -> DoubleArray(3) { v[it] / norm(v) } }
        val mean = DoubleArray(3) { i -> directions.map { it[i] }.average() }
        val cov = Array2DRowRealMatrix(3,3)
        directions.forEach { v -> repeat(3) { i -> repeat(3) { j ->
            cov.addToEntry(i, j, (v[i] - mean[i]) * (v[j] - mean[j]))
        } } }
        val eigen = EigenDecomposition(cov).realEigenvalues.sorted()
        require(eigen[0] > 0.0 && eigen[0]/eigen[2] >= 0.15) {
            "Insufficient 3D angular coverage"
        }
        val aEigen = EigenDecomposition(solution.a).realEigenvalues
        require(aEigen.all { it in 0.5..2.0 }) { "Soft-iron matrix outside limits" }
        require(norm(solution.offset) <= 80.0) { "MAG installation offset >80 uT" }
        val residues = radii.map { abs(it - radius) }.sorted()
        val rms = sqrt(radii.map { (it-radius)*(it-radius) }.average())
        val p95 = residues[((residues.size - 1) * 0.95).toInt()]
        require(rms <= 2.5 && rms/radius <= 0.05 && p95/radius <= 0.10) {
            "3D fit residuals too high / mixed field"
        }
        return RegattaLinkMagFit(
            offset = solution.offset,
            matrix = DoubleArray(9) { i -> solution.a.getEntry(i/3, i%3) },
            radiusUt = radius, rmsUt = rms, p95Ut = p95,
            usedSectors = inliers.size,
            rejectedSectors = points.size - inliers.size,
            octants = octants, eigenRatio = eigen[0]/eigen[2]
        )
    }
}
