# Offline artifact lab

This Python laboratory verifies synthetic merchant checkout ES256 signatures and content bindings without invoking the Java payment core, a provider or a network service. Its profile is `offline-ap2-acp-artifact-lab-0.1`; native AP2 authority and interoperability remain unverified. Read [the implementation contract and commands](../../docs/offline-artifact-lab.md).

```powershell
./.venv/Scripts/python.exe -m unittest discover -s integrations/offline_lab/tests
./.venv/Scripts/python.exe -m integrations.offline_lab
```

The nine upstream schema files must already be present in the ignored source cache and match the existing immutable upstream lock. [Required source references](required-source-artifacts.json) identify them. There is no runtime schema download. [Binary dependency hashes](requirements-crypto.lock.txt) and [dependency/RFC provenance](dependency-provenance.json) are separate from upstream protocol artifacts. The disabled real bridge profile is untouched.
