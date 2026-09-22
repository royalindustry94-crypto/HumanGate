package ai.leon.companion.rig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leon's skeleton plus its drawable layers. Bones are stored parent-before-child so a single
 * forward pass resolves the whole hierarchy; parts are stored in draw order.
 *
 * <p>Build one with {@link Builder}. After construction the topology is fixed — only the per-frame
 * animation fields on {@link Bone} and {@link RigPart} change, so there is no allocation in the
 * render loop.
 */
public final class Rig {
    private final List<Bone> bones;
    private final Map<String, Bone> boneIndex;
    private final List<RigPart> parts;
    private final Map<String, RigPart> partIndex;
    private final Map<String, List<RigPart>> groups;

    /** Author-space design size of the rig; the renderer scales this to the overlay size. */
    public final float designWidth;
    public final float designHeight;

    private Rig(Builder b) {
        this.designWidth = b.designWidth;
        this.designHeight = b.designHeight;
        this.bones = Collections.unmodifiableList(new ArrayList<>(b.bones));
        this.boneIndex = Collections.unmodifiableMap(new LinkedHashMap<>(b.boneIndex));

        List<RigPart> sorted = new ArrayList<>(b.parts);
        sorted.sort(Comparator.comparingInt(p -> p.z));
        this.parts = Collections.unmodifiableList(sorted);

        Map<String, RigPart> pIndex = new LinkedHashMap<>();
        Map<String, List<RigPart>> g = new LinkedHashMap<>();
        for (RigPart p : sorted) {
            pIndex.put(p.name, p);
            if (p.group != null) {
                List<RigPart> list = g.get(p.group);
                if (list == null) {
                    list = new ArrayList<>();
                    g.put(p.group, list);
                }
                list.add(p);
            }
        }
        this.partIndex = Collections.unmodifiableMap(pIndex);
        Map<String, List<RigPart>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<RigPart>> e : g.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        this.groups = Collections.unmodifiableMap(frozen);
    }

    /** Bones in parent-before-child order. */
    public List<Bone> bones() {
        return bones;
    }

    /** Parts in ascending z (back to front) order. */
    public List<RigPart> parts() {
        return parts;
    }

    public Bone bone(String name) {
        Bone b = boneIndex.get(name);
        if (b == null) throw new IllegalArgumentException("unknown bone: " + name);
        return b;
    }

    public Bone findBone(String name) {
        return boneIndex.get(name);
    }

    public RigPart part(String name) {
        RigPart p = partIndex.get(name);
        if (p == null) throw new IllegalArgumentException("unknown part: " + name);
        return p;
    }

    public RigPart findPart(String name) {
        return partIndex.get(name);
    }

    /** Members of a swap group in draw order, or an empty list if the group does not exist. */
    public List<RigPart> group(String name) {
        List<RigPart> list = groups.get(name);
        return list == null ? Collections.<RigPart>emptyList() : list;
    }

    /** Clears every animated field back to the rest pose. */
    public void resetAnimation() {
        for (int i = 0; i < bones.size(); i++) bones.get(i).resetAnimation();
        for (int i = 0; i < parts.size(); i++) parts.get(i).resetAnimation();
    }

    /** Resolves every bone then every part. Allocation-free. */
    public void solve() {
        for (int i = 0; i < bones.size(); i++) bones.get(i).solve();
        for (int i = 0; i < parts.size(); i++) parts.get(i).solve();
    }

    public static final class Builder {
        private final List<Bone> bones = new ArrayList<>();
        private final Map<String, Bone> boneIndex = new LinkedHashMap<>();
        private final List<RigPart> parts = new ArrayList<>();
        private float designWidth = 512f;
        private float designHeight = 768f;

        public Builder design(float width, float height) {
            if (width <= 0f || height <= 0f) throw new IllegalArgumentException("design size must be positive");
            this.designWidth = width;
            this.designHeight = height;
            return this;
        }

        public Builder root(String name, float x, float y) {
            return bone(name, null, x, y, 0f, 1f, 1f);
        }

        public Builder bone(String name, String parentName, float x, float y) {
            return bone(name, parentName, x, y, 0f, 1f, 1f);
        }

        public Builder bone(String name, String parentName, float x, float y, float rotationDeg,
                            float scaleX, float scaleY) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("bone needs a name");
            if (boneIndex.containsKey(name)) throw new IllegalArgumentException("duplicate bone: " + name);
            Bone parent = null;
            if (parentName != null) {
                parent = boneIndex.get(parentName);
                // Parent-before-child ordering is required for the single-pass solve.
                if (parent == null) throw new IllegalArgumentException(
                        "bone '" + name + "' declared before its parent '" + parentName + "'");
            }
            Bone bone = new Bone(name, parent, x, y, rotationDeg, scaleX, scaleY);
            bones.add(bone);
            boneIndex.put(name, bone);
            return this;
        }

        public Builder part(String name, String artKey, String boneName, float offsetX, float offsetY,
                            float width, float height, int z) {
            return part(name, artKey, boneName, offsetX, offsetY, width, height, 0.5f, 0.5f, 0f, z, null);
        }

        public Builder part(String name, String artKey, String boneName, float offsetX, float offsetY,
                            float width, float height, float pivotX, float pivotY,
                            float rotationDeg, int z, String group) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("part needs a name");
            Bone bone = boneIndex.get(boneName);
            if (bone == null) throw new IllegalArgumentException(
                    "part '" + name + "' references unknown bone '" + boneName + "'");
            if (width <= 0f || height <= 0f) throw new IllegalArgumentException(
                    "part '" + name + "' needs a positive size");
            for (RigPart existing : parts) {
                if (existing.name.equals(name)) throw new IllegalArgumentException("duplicate part: " + name);
            }
            parts.add(new RigPart(name, artKey == null ? name : artKey, bone, offsetX, offsetY,
                    width, height, pivotX, pivotY, rotationDeg, z, group));
            return this;
        }

        public Rig build() {
            if (bones.isEmpty()) throw new IllegalStateException("rig has no bones");
            if (parts.isEmpty()) throw new IllegalStateException("rig has no parts");
            return new Rig(this);
        }
    }
}
