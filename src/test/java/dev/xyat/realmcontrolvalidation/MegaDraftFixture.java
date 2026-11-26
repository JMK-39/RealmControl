
package dev.xyat.realmcontrolvalidation;

import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;

/** Stages a draft in an isolated game directory for the natural generation test mod. */
final class MegaDraftFixture {
    private MegaDraftFixture() {
    }

    static Path begin(StructureProject project, boolean legacy) throws IOException {
        Path folder = ProjectStore.folder(project.id);
        if (!legacy) return folder.resolve("@scans").resolve(UUID.randomUUID().toString());
        ProjectStore.save(project);
        return folder;
    }

    static void finish(StructureProject project, boolean legacy, Path scan, Cutouts cutouts, BitSet empty) throws IOException {
        if (legacy) {
            ProjectStore.saveCutouts(project.id, cutouts);
            return;
        }
        Files.writeString(scan.resolve("cutouts.json"), cutouts.toJson().toString());
        Files.writeString(scan.resolve("markers.json"), new Markers().toJson().toString());
        Files.write(scan.resolve("empty-tiles.bin"), empty.toByteArray());
        var metadata = project.toJson();
        metadata.addProperty("scan_generation", scan.getFileName().toString());
        Files.writeString(ProjectStore.folder(project.id).resolve("project.json"), metadata.toString());
        try (var draft = ProjectStore.openDraft(project.id)) {
            if (draft == null || !draft.isEmptyTile(1, 1) || draft.readTile(0, 0) == null) {
                throw new IOException("The stored scan fixture did not reopen with its sparse tile map: " + scan);
            }
        }
    }
}
