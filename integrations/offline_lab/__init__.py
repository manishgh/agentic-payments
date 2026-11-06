"""Synthetic, offline artifact checks. This package has no payment execution port."""

PROFILE = "offline-ap2-acp-artifact-lab-0.1"


class LabRefusal(ValueError):
    """Fixed safe code only: never include the rejected artifact or key material."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)
