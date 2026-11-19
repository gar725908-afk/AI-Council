package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusModelCenterTest {
    @Test fun gemmaE2BIsBalancedOnDeviceCandidate() {
        val gemma = NexusModelCatalog.find("google-gemma-4-e2b-it")
        assertEquals("Gemma 4 E2B", gemma?.displayName)
        assertEquals(ModelExecutionTarget.ANDROID_ON_DEVICE, gemma?.executionTarget)
        assertTrue(gemma?.recommendedForBalancedProfile == true)
        assertTrue(gemma?.runtimes?.contains(ModelRuntimeKind.LITERT_LM) == true)
    }

    @Test fun officialGemmaArtifactIsPinnedAndDownloadableOnlyAfterLicenseAcceptance() {
        val gemma = NexusModelCatalog.find("google-gemma-4-e2b-it")!!
        assertTrue(gemma.isDownloadable)
        assertEquals(2_340_870_772L, gemma.artifact?.sizeBytes)
        assertEquals("Apache-2.0", gemma.artifact?.licenseId)
        assertTrue(ModelDownloadPolicy.evaluate(
            gemma, ModelDownloadRequest(gemma.id, Long.MAX_VALUE, null)
        ) is ModelDownloadDecision.Rejected)
        assertTrue(ModelDownloadPolicy.evaluate(
            gemma, ModelDownloadRequest(gemma.id, Long.MAX_VALUE, "Apache-2.0")
        ) is ModelDownloadDecision.Allowed)
        val qwen = NexusModelCatalog.find("qwen3-0.6b")!!
        assertTrue(qwen.isDownloadable)
        assertEquals(ModelRuntimeKind.LLAMA_CPP, qwen.artifact?.runtime)
        assertEquals(428_970_080L, qwen.artifact?.sizeBytes)
        assertTrue(ModelDownloadPolicy.evaluate(
            qwen, ModelDownloadRequest(qwen.id, Long.MAX_VALUE, "Apache-2.0")
        ) is ModelDownloadDecision.Allowed)
    }

    @Test fun downloadRequiresChecksumSpaceAndExactLicense() {
        val manifest = ModelArtifactManifest("trusted-catalog", "revision-123", "gemma-license",
            "model.litertlm", 1000, "a".repeat(64), ModelRuntimeKind.LITERT_LM,
            "https://models.example.test/model.litertlm")
        val model = NexusModelDescriptor("test", "Test", "Test", ModelExecutionTarget.ANDROID_ON_DEVICE,
            setOf(ModelRuntimeKind.LITERT_LM), setOf(ModelTaskCapability.CHAT), "test", artifact = manifest)
        assertTrue(ModelDownloadPolicy.evaluate(model, ModelDownloadRequest("test", 999, "gemma-license")) is ModelDownloadDecision.Rejected)
        assertTrue(ModelDownloadPolicy.evaluate(model, ModelDownloadRequest("test", 1000, null)) is ModelDownloadDecision.Rejected)
        assertTrue(ModelDownloadPolicy.evaluate(model, ModelDownloadRequest("test", 1000, "gemma-license")) is ModelDownloadDecision.Allowed)
    }

    @Test fun evolutionRequiresApprovalAndCreatesRollbackPoint() {
        val proposal = validProposal()
        assertTrue(NexusEvolutionPolicy.activate(proposal, EvolutionApproval.Rejected("Denied")).isFailure)
        val accepted = NexusEvolutionPolicy.activate(proposal, EvolutionApproval.Approved)
        assertTrue(accepted.isSuccess)
        assertEquals(8L, accepted.getOrThrow().activeVersion)
        assertEquals(7L, accepted.getOrThrow().rollbackToVersion)
    }

    @Test fun invalidEvolutionVersionRejectedAndWeightsNeverMutateSilently() {
        assertTrue(NexusEvolutionPolicy.activate(validProposal().copy(proposedVersion = 10), EvolutionApproval.Approved).isFailure)
        assertFalse(NexusEvolutionPolicy.allowsSilentWeightMutation())
    }

    private fun validProposal() = EvolutionProposal("proposal-1", EvolutionChangeKind.SKILL_PACK,
        "Improve research workflow", "Repeated verification failures", 7, 8, "b".repeat(64), 1_800_000_000_000)
}
