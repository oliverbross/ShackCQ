# Hamlib capability model

The pinned authority is Hamlib 4.7.2 at commit 40f63488fe0bd751b147f48d62fd217bf53713a0, archive SHA-256 ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f.

The Agent advertises stable model ID, model label, backend, frequency envelope, modes, filters, setters, meters, read-only TX state, and a declared no-movement rotator envelope. The browser renders only advertised setters. The local Agent independently revalidates every value.

Hamlib exposes the Elecraft KX3 model. This pin has 37 backends and no dedicated RGO ONE model. RGO ONE must not be selected through a guessed compatible Hamlib model. Physical RGO acceptance is therefore NOT SUPPORTED BY HAMLIB for this phase.

Run python3 scripts/check_hamlib_upstream.py --check-latest before updating the pin. A new stable release requires a separate provenance and regression review.
