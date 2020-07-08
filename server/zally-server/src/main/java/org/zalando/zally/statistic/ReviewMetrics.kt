package org.zalando.zally.statistic

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.zalando.zally.apireview.ApiReviewRepository

@Component
class ReviewMetrics(private val apiReviewRepository: ApiReviewRepository, private val meterRegistry: MeterRegistry) {

    final val statisticsReferences = mutableListOf<StatisticReference>()

    @Value("\${metrics.review.name-prefix:zally_}")
    lateinit var metricsNamePrefix: String

    @Scheduled(fixedDelayString = "\${metrics.review.fixed-delay:300000}", initialDelay = 10000)
    fun updateMetrics() {
        LOG.debug("Updating metrics for review statistics")
        apiReviewRepository.findLatestApiReviews()
            .map(ReviewStatisticsByName.Factory::of)
            .forEach { statistic ->
                statisticsReferences
                    .filter { it.statisticName == statistic.name }
                    .map { reference ->
                        LOG.debug("Updating metrics reference values for review statistic ${statistic.name}")
                        reference.metricValueFor(MetricName.NUMBER_OF_ENDPOINTS).set(statistic.numberOfEndpoints)
                        reference.metricValueFor(MetricName.MUST_VIOLATIONS).set(statistic.mustViolations)
                        reference.metricValueFor(MetricName.SHOULD_VIOLATIONS).set(statistic.shouldViolations)
                        reference.metricValueFor(MetricName.MAY_VIOLATIONS).set(statistic.mayViolations)
                        reference.metricValueFor(MetricName.HINT_VIOLATIONS).set(statistic.hintViolations)
                    }
                    .ifEmpty {
                        LOG.debug("Creating new metrics reference for review statistic ${statistic.name}")
                        val reference = StatisticReference.of(statistic)
                        statisticsReferences.add(reference)
                        registerGaugeMetric(reference, statistic.customLabels)
                    }
            }
    }

    private fun registerGaugeMetric(reference: StatisticReference, customLabels: Map<String, String>) {
        val snakeCasedApiName = reference.statisticName.replace(Regex("\\p{Zs}+"), "_").toLowerCase()
        reference.metricPair.forEach { metricPair ->
            val metricName = "${metricsNamePrefix}${metricPair.metricName.value}"
            val gaugeBuilder = Gauge.builder(metricName, metricPair.metricValue, { v -> v.toDouble() }).tag("api_name", snakeCasedApiName)
            customLabels.forEach { (k, v) -> gaugeBuilder.tag(k, v) }
            gaugeBuilder.register(meterRegistry)
            LOG.debug("Registered micrometer gauge $metricName for api $snakeCasedApiName")
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ReviewMetrics::class.java)
    }
}
