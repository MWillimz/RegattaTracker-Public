package de.williserv.regattaclient

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.linear.SingularValueDecomposition
import kotlin.math.*

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

    private fun solve(points: List<DoubleArray>, weights: List<Double>): Solution {
        val weightSum = weights.sum()
        val origin = DoubleArray(3) { col -> points.indices.sumOf { points[it][col]*weights[it] } / weightSum }
        val scale = median(points.map { p ->
            norm(DoubleArray(3) { p[it] - origin[it] })
        }.toDoubleArray())
        require(scale >= 5.0 && scale.isFinite()) { "MAG capture span too small" }
        val design = Array2DRowRealMatrix(points.size, 9)
        val target = DoubleArray(points.size)
        points.forEachIndexed { row, p ->
            val rootWeight = sqrt(weights[row] / (weightSum / weights.size))
            target[row] = rootWeight
            val x = (p[0] - origin[0]) / scale
            val y = (p[1] - origin[1]) / scale
            val z = (p[2] - origin[2]) / scale
            arrayOf(x*x, y*y, z*z, 2*x*y, 2*x*z, 2*y*z, x, y, z)
                .forEachIndexed { col, value -> design.setEntry(row, col, value * rootWeight) }
        }
        val svd = SingularValueDecomposition(design)
        val singular = svd.singularValues
        require(svd.rank == 9 && singular.last() > 0.0 &&
            singular.first() / singular.last() <= 1e5) { "MAG ellipsoid ill-conditioned" }
        val c = svd.solver.solve(ArrayRealVector(target)).toArray()
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
        val normal = Array2DRowRealMatrix(a.scalarMultiply(
            1.0 / det.pow(1.0/3.0)).data)
        return Solution(DoubleArray(3) { origin[it] + scale * center[it] }, normal)
    }

    private fun corrected(p: DoubleArray, s: Solution): DoubleArray {
        val translated = ArrayRealVector(DoubleArray(3) { p[it] - s.offset[it] })
        return s.a.operate(translated).toArray()
    }

    private fun groups(corrected: List<DoubleArray>): List<Int> = corrected.map { v ->
        val r = norm(v)
        require(r.isFinite() && r >= 1.0) { "Invalid corrected MAG geometry" }
        val band = (((v[2]/r+1)*6).toInt()).coerceIn(0,11)
        val az = (atan2(v[1],v[0])+2*PI) % (2*PI)
        band*12 + (az*6/PI).toInt().coerceIn(0,11)
    }

    private fun balanced(groups: List<Int>): List<Double> {
        val counts = groups.groupingBy { it }.eachCount()
        return groups.map { 1.0/counts.getValue(it) }
    }

    fun fit(archive: RegattaLinkMagArchive): RegattaLinkMagFit {
        // Physical archive labels and per-cell medians are never geometry evidence.
        val points = archive.sectors.flatMap { cell -> cell.samples.map { doubleArrayOf(it.x,it.y,it.z) } }
        require(points.size >= 36 && points.all { it.all(Double::isFinite) }) { "Collect >=36 finite original XYZ samples" }
        var mask = points.indices.toList()
        var weights = List(points.size) { 1.0 }
        for (iteration in 0 until 6) {
            val solution = solve(mask.map { points[it] }, mask.map { weights[it] })
            val corrected = points.map { corrected(it,solution) }
            val radii = corrected.map(::norm)
            val radius = magMedian(mask.map { radii[it] })
            val errors = radii.map { abs(it-radius) }
            val med = magMedian(mask.map { errors[it] })
            val mad = magMedian(mask.map { abs(errors[it]-med) })
            val next = points.indices.filter { errors[it] <= max(radius*0.065, med+3.5*1.4826*mad) }
            require(next.size >= max(36, ceil(0.8*points.size).toInt())) { "More than 20% inconsistent original MAG samples" }
            val analysis = groups(corrected)
            val balanced = balanced(next.map { analysis[it] })
            val newWeights = MutableList(points.size) { 1.0 }
            next.forEachIndexed { k, i -> newWeights[i] = balanced[k] }
            val stable = mask == next && weights.indices.all { abs(weights[it]-newWeights[it]) < 1e-8 }
            mask = next; weights = newWeights
            if (stable) break
        }
        val solution = solve(mask.map { points[it] }, mask.map { weights[it] })
        val allCorrected = points.map { corrected(it,solution) }
        val allRadii = allCorrected.map(::norm)
        val centerRadius = magMedian(allRadii)
        val errors = allRadii.map { abs(it-centerRadius) }
        val med = magMedian(errors)
        val mad = magMedian(errors.map { abs(it-med) })
        mask = points.indices.filter { errors[it] <= max(centerRadius*0.065, med+3.5*1.4826*mad) }
        require(mask.size >= max(36, ceil(0.8*points.size).toInt())) { "More than 20% inconsistent original MAG samples" }
        val corrected = mask.map { allCorrected[it] }
        val radii = mask.map { allRadii[it] }
        val analysis = groups(corrected)
        val used = analysis.toSet().size
        require(used >= 36) { "3D fit requires >=36 observed direction groups" }
        val rawWeights = balanced(analysis)
        val sum = rawWeights.sum()
        val w = rawWeights.map { it/sum }
        val radius = radii.indices.sumOf { w[it]*radii[it] }
        require(radius in 10.0..100.0) { "Magnetic field outside 10-100 uT" }
        val octants = corrected.map { v ->
            (if(v[0]>=0) 1 else 0) or (if(v[1]>=0) 2 else 0) or (if(v[2]>=0) 4 else 0)
        }.toSet().size
        require(octants >= 7) { "3D calibration requires >=7 spatial octants" }
        val directions = corrected.map { v -> DoubleArray(3) { v[it]/norm(v) } }
        val mean = DoubleArray(3) { k -> directions.indices.sumOf { w[it]*directions[it][k] } }
        val cov = Array2DRowRealMatrix(3,3)
        directions.forEachIndexed { k, v -> repeat(3) { i -> repeat(3) { j ->
            cov.addToEntry(i,j,w[k]*(v[i]-mean[i])*(v[j]-mean[j]))
        } } }
        val eigen = EigenDecomposition(cov).realEigenvalues.sorted()
        val trace = cov.trace/3.0
        val condition = org.apache.commons.math3.linear.LUDecomposition(cov).determinant / trace.pow(3)
        require(eigen[0] > 0 && condition >= 0.25) { "Insufficient 3D angular coverage" }
        require(EigenDecomposition(solution.a).realEigenvalues.all { it in 0.5..2.0 }) { "Soft-iron matrix outside limits" }
        val matrix = DoubleArray(9) { solution.a.getEntry(it/3,it%3) }
        validateMag3Numbers(solution.offset, matrix)
        val residuals = radii.map { abs(it-radius) }
        val rms = sqrt(radii.indices.sumOf { w[it]*residuals[it]*residuals[it] })
        val ordered = residuals.indices.sortedBy { residuals[it] }
        var cumulative = 0.0
        val p95 = residuals[ordered.first { cumulative += w[it]; cumulative >= 0.95-1e-12 }]
        val tail = radii.indices.filter { residuals[it] > 0.1*radius }.sumOf { w[it] }
        require(rms <= 2.5 && rms/radius <= 0.05 && tail <= 0.05) { "3D fit residuals too high / mixed field" }
        return RegattaLinkMagFit(solution.offset, matrix, radius, rms, p95, used,
            points.size-mask.size, octants, eigen[0]/eigen[2])
    }
}
