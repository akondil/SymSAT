package lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An E-Class represents a set of equivalent ENodes.
 */
public class EClass {
    public final int id;
    public final Set<ENode> nodes = new HashSet<>();
    public final List<ParentRef> parents = new ArrayList<>();

    public EClass(int id) {
        this.id = id;
    }

    public static class ParentRef {
        public final ENode node;
        public final int classId;

        public ParentRef(ENode node, int classId) {
            this.node = node;
            this.classId = classId;
        }
    }
}
