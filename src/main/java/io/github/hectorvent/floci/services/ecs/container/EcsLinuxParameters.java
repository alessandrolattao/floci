package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A container definition's {@code linuxParameters}, applied to the container it launches as ECS
 * documents each member: {@code initProcessEnabled} is {@code --init}, {@code capabilities} are
 * {@code --cap-add} and {@code --cap-drop}, {@code devices} are {@code --device} (read, write and
 * mknod when no permissions are given), {@code sharedMemorySize} is {@code --shm-size} in MiB,
 * each {@code tmpfs} entry is a {@code --tmpfs} of its size in MiB with its mount options,
 * {@code maxSwap} is {@code --memory-swap} as the container's memory plus that many MiB, and
 * {@code swappiness} is {@code --memory-swappiness}, which registration requires with
 * {@code maxSwap}, as ECS does, and which is ignored without it.
 *
 * <p>RegisterTaskDefinition keeps the members verbatim among the definition's unparsed members,
 * which is where they are read from, whether the definition came from the API or a template.
 */
final class EcsLinuxParameters {

    private static final long MIB = 1024L * 1024L;

    private EcsLinuxParameters() {
    }

    /**
     * @param memoryLimitMb the hard memory limit the container runs under, which {@code maxSwap}
     *        is added to; without one Docker cannot limit swap, so {@code maxSwap} is not applied
     */
    static void apply(ContainerBuilder.Builder spec, ContainerDefinition def, Integer memoryLimitMb) {
        Object raw = def.getUnparsed() == null ? null : def.getUnparsed().get("linuxParameters");
        if (!(raw instanceof Map<?, ?> parameters)) {
            return;
        }
        if (Boolean.TRUE.equals(parameters.get("initProcessEnabled"))) {
            spec.withInit();
        }
        if (parameters.get("capabilities") instanceof Map<?, ?> capabilities) {
            strings(capabilities.get("add")).forEach(spec::withCapAdd);
            strings(capabilities.get("drop")).forEach(spec::withCapDrop);
        }
        if (parameters.get("devices") instanceof List<?> devices) {
            for (Object item : devices) {
                if (item instanceof Map<?, ?> device && device.get("hostPath") instanceof String hostPath) {
                    String containerPath = device.get("containerPath") instanceof String path && !path.isBlank()
                            ? path : hostPath;
                    spec.withDevice(hostPath, containerPath, cgroupPermissions(strings(device.get("permissions"))));
                }
            }
        }
        Long sharedMemorySize = number(parameters.get("sharedMemorySize"));
        if (sharedMemorySize != null) {
            spec.withShmSizeBytes(sharedMemorySize * MIB);
        }
        if (parameters.get("tmpfs") instanceof List<?> mounts) {
            for (Object item : mounts) {
                if (item instanceof Map<?, ?> mount && mount.get("containerPath") instanceof String containerPath
                        && mount.get("size") instanceof Number size) {
                    List<String> options = new ArrayList<>(strings(mount.get("mountOptions")));
                    options.add("size=" + size.longValue() + "m");
                    spec.withTmpfs(containerPath, String.join(",", options));
                }
            }
        }
        Long maxSwap = number(parameters.get("maxSwap"));
        if (maxSwap != null && memoryLimitMb != null) {
            spec.withMemorySwapBytes((memoryLimitMb + maxSwap) * MIB);
            Long swappiness = number(parameters.get("swappiness"));
            if (swappiness != null) {
                spec.withMemorySwappiness(swappiness);
            }
        }
    }

    /** ECS's {@code read}, {@code write} and {@code mknod} as Docker's cgroup permission letters. */
    private static String cgroupPermissions(List<String> permissions) {
        if (permissions.isEmpty()) {
            return "rwm";
        }
        StringBuilder letters = new StringBuilder();
        if (permissions.contains("read")) {
            letters.append('r');
        }
        if (permissions.contains("write")) {
            letters.append('w');
        }
        if (permissions.contains("mknod")) {
            letters.append('m');
        }
        return letters.toString();
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String text) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
