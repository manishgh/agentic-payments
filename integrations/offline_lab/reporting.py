"""An explicit safe projection; never serialize arbitrary artifact objects or exceptions."""

from . import PROFILE, LabRefusal
from .binding import ContentBinding
from .jws import VerifiedCheckoutArtifact
from .ledger import LabOperation


def safe_report(artifact: VerifiedCheckoutArtifact, binding: ContentBinding, operation: LabOperation) -> dict:
    if (artifact.artifact_id, artifact.merchant_id, artifact.session_id, artifact.revision, artifact.payload_sha256, artifact.checkout_hash) != (
            binding.artifact_id, binding.merchant_id, binding.session_id, binding.revision, binding.payload_sha256, binding.checkout_hash):
        raise LabRefusal("REPORT_ARTIFACT_BINDING_MISMATCH")
    if operation.binding_id != binding.binding_id or operation.artifact_id != artifact.artifact_id:
        raise LabRefusal("REPORT_OPERATION_BINDING_MISMATCH")
    if binding.authority != "CONTENT_BOUND_AUTHORITY_UNVERIFIED" or operation.native_ap2_authority_verified or operation.financial_state != "UNKNOWN":
        raise LabRefusal("REPORT_UNSUPPORTED_AUTHORITY_OR_FINANCIAL_CLAIM")
    return {"profile": PROFILE, "synthetic_data": True, "merchant_signature_valid": True,
            "content_binding": binding.authority, "artifact_id": artifact.artifact_id,
            "binding_id": binding.binding_id, "operation_id": operation.operation_id,
            "owner": operation.owner, "commerce_state": operation.commerce_state,
            "financial_state": operation.financial_state, "native_ap2_authority_verified": False,
            "provider_interoperability": False, "payment_dispatch_available": False}
