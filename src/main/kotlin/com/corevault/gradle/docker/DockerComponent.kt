package com.corevault.gradle.docker

import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Usage
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.attributes.AttributesFactory
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.component.UsageContext
import org.gradle.api.model.ObjectFactory

// CHECKSTYLE:ON
class DockerComponent(
    dockerArtifact: PublishArtifact?,
    runtimeDependencies: DependencySet,
    objectFactory: ObjectFactory,
    attributesFactory: AttributesFactory,
) : SoftwareComponentInternal {
    private val runtimeUsage: UsageContext
    private val artifacts: MutableSet<PublishArtifact> = LinkedHashSet()
    private val runtimeDependencies: DependencySet

    init {
        dockerArtifact?.let { artifacts.add(it) }
        this.runtimeDependencies = runtimeDependencies
        val usage = objectFactory.named(Usage::class.java, Usage.JAVA_RUNTIME)
        val attributes = attributesFactory.of(Usage.USAGE_ATTRIBUTE, usage)
        runtimeUsage = RuntimeUsageContext(usage, attributes)
    }

    override fun getName(): String = "docker"

    override fun getUsages(): MutableSet<UsageContext> = mutableSetOf(runtimeUsage)

    private inner class RuntimeUsageContext(
        private val usage: Usage,
        private val attributes: ImmutableAttributes,
    ) : UsageContext {

        override fun getArtifacts(): MutableSet<PublishArtifact> = artifacts

        override fun getDependencies(): Set<ModuleDependency> =
            runtimeDependencies.withType(ModuleDependency::class.java)

        override fun getName(): String = "runtime"

        override fun getAttributes(): AttributeContainer = attributes

        override fun getDependencyConstraints(): MutableSet<DependencyConstraint> = mutableSetOf()

        override fun getCapabilities(): MutableSet<Capability> = mutableSetOf()

        override fun getGlobalExcludes(): MutableSet<ExcludeRule> = mutableSetOf()
    }
}
