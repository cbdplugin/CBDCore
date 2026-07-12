package com.cbd.cbdcore.motd;

/** MOTD 한 줄과 그 줄의 가운데 정렬 기준 너비(px). */
public record MotdLine(String text, int targetWidth) {
}
