package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An UpdateStack that submits the template the stack already has is refused with "No updates are
 * to be performed.", as on AWS. A test that re-applies the same resources to exercise an update
 * gives the template a new Description, which is what forces such an update on AWS too.
 */
final class CfnUpdates {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CfnUpdates() {
    }

    /** {@code template} with a Description no deployed template carries. */
    static String forceUpdate(String template) {
        String description = "forced update " + System.nanoTime();
        String trimmed = template.stripLeading();
        if (!trimmed.startsWith("{")) {
            return "Description: " + description + "\n" + template;
        }
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(template);
            node.put("Description", description);
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a JSON template", e);
        }
    }
}
