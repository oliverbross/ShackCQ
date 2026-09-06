#include "shackcq/core.h"

#include <cassert>

int main() {
    for (int cycle = 0; cycle < 1000; ++cycle) {
        shackcq_feature_context *features = shackcq_feature_context_create();
        assert(features != nullptr);
        shackcq_feature_set_watchlist(features, "OM0RX\nVK9AA");
        shackcq_feature_context_destroy(features);
    }
    for (int cycle = 0; cycle < 500; ++cycle) {
        shackcq_context *radio = shackcq_context_create();
        assert(radio != nullptr);
        shackcq_context_destroy(radio);

        shackcq_panadapter_context *panadapter = shackcq_panadapter_context_create();
        assert(panadapter != nullptr);
        shackcq_panadapter_context_destroy(panadapter);
    }
    return 0;
}
