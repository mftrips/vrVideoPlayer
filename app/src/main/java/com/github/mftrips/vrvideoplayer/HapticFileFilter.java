package com.github.mftrips.vrvideoplayer;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

public class HapticFileFilter implements FileFilter {
    private static List<String> knownExtensions = new ArrayList<String>(){{
        add("funscript");
        add("json");
        add("js");
        add("launch");
        add("meta");
        add("txt");
        add("ini");
//        add("beats");
        add("csv");
    }};
    private File videoFile;

    HapticFileFilter(File videoFile) {
        this.videoFile = videoFile;
    }

    @Override
    public boolean accept(File file) {
        boolean accept = file.canRead() &&
                file != this.videoFile &&
                HapticFileFilter.knownExtensions.contains(FilenameUtils.getExtension(file.getName())) &&
                FilenameUtils.getBaseName(file.getName()).equals(FilenameUtils.getBaseName(this.videoFile.getName()));
        if (accept) {
            System.out.println(String.format("Accepting %s", file.getName()));
        } else {
            if (file.getName().equals("VirtualRealPorn.com_-_And_the_Oscar_goes_to_-_1920_-_y6RtdFG42T.ini")) {
                System.out.println(String.format("Skipping %s (%s)", file.getName(), FilenameUtils.getBaseName(this.videoFile.getName())));
                if (!file.canRead()) System.out.println("can't read");
                if (file == this.videoFile) System.out.println("same as video");
                if (!HapticFileFilter.knownExtensions.contains(FilenameUtils.getExtension(file.getName()))) System.out.println(
                        String.format("unknown extension: %s", FilenameUtils.getExtension(file.getName())));
                if (!FilenameUtils.getBaseName(file.getName()).equals(FilenameUtils.getBaseName(this.videoFile.getName()))) System.out.println(
                        String.format("bad basename: %s", FilenameUtils.getBaseName(file.getName())));
            }
        }
        return accept;
    }
}
