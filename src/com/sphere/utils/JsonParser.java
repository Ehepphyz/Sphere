package com.sphere.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonParser {
    public static class ModuleInfo {
        public String name;
        public String version;
        public String latestVersion;

        public ModuleInfo(String name, String version, String latestVersion) {
            this.name = name;
            this.version = version;
            this.latestVersion = latestVersion;
        }
    }

    public static List<ModuleInfo> parseModuleList(String json) {
        List<ModuleInfo> list = new ArrayList<>();
        // Updated regex to capture name, version, and optional latest_version
        Pattern pattern = Pattern.compile("\\{\"name\":\\s*\"(.*?)\",\\s*\"version\":\\s*\"(.*?)\"(?:,\\s*\"latest_version\":\\s*\"(.*?)\")?");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            list.add(new ModuleInfo(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return list;
    }
}
