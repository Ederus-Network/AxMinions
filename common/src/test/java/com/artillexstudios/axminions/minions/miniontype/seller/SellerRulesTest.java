package com.artillexstudios.axminions.minions.miniontype.seller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Ederus: plain checks of the seller minion rules, without Bukkit and without a test framework.
 * Run the main; it exits with 1 and says which check failed.
 */
public final class SellerRulesTest {
    private static int checks;

    private SellerRulesTest() {
    }

    private static void same(String what, double expected, double actual) {
        checks++;
        if (Math.abs(expected - actual) > 1e-9) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void same(String what, int expected, int actual) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void yes(String what, boolean value) {
        checks++;
        if (!value) {
            throw new AssertionError(what);
        }
    }

    public static void main(String[] args) throws Exception {
        durability();
        level();
        partial();
        limits();
        days();
        persistence();
        System.out.println("PASS SellerRulesTest, " + checks + " checks");
    }

    private static void durability() {
        same("new bow keeps the whole price", 1.0, SellerRules.durabilityFactor(384, 0));
        same("bow at half pays half", 0.5, SellerRules.durabilityFactor(384, 192));
        same("broken bow pays nothing", 0.0, SellerRules.durabilityFactor(384, 384));
        same("damage over the max counts as broken", 0.0, SellerRules.durabilityFactor(384, 9999));
        same("negative damage counts as new", 1.0, SellerRules.durabilityFactor(384, -5));
        same("item without durability keeps the whole price", 1.0, SellerRules.durabilityFactor(0, 7));

        same("bow of 2 at half", 1.0, SellerRules.damagedPrice(2.0, 384, 192));
        same("iron chestplate of 480 at a quarter of damage", 360.0, SellerRules.damagedPrice(480.0, 240, 60));
        same("never over the new price", 480.0, SellerRules.damagedPrice(480.0, 240, 0));
        same("no price new, no price worn", 0.0, SellerRules.damagedPrice(0.0, 384, 10));
        same("negative price is no price", 0.0, SellerRules.damagedPrice(-3.0, 384, 10));
        same("broken price is no price", 0.0, SellerRules.damagedPrice(Double.NaN, 384, 10));
        same("endless price is no price", 0.0, SellerRules.damagedPrice(Double.POSITIVE_INFINITY, 384, 10));
    }

    private static void level() {
        yes("level 9 does not sell worn gear", !SellerRules.sellsDamaged(9, 10));
        yes("level 10 sells worn gear", SellerRules.sellsDamaged(10, 10));
        yes("level 11 sells worn gear", SellerRules.sellsDamaged(11, 10));
        yes("from-level 0 turns it off", !SellerRules.sellsDamaged(10, 0));
        yes("negative from-level turns it off", !SellerRules.sellsDamaged(10, -1));
    }

    private static void partial() {
        same("half the pile, half the price", 50.0, SellerRules.partialPrice(100.0, 5, 10));
        same("whole pile, whole price", 100.0, SellerRules.partialPrice(100.0, 10, 10));
        same("more than the pile is the whole price", 100.0, SellerRules.partialPrice(100.0, 64, 10));
        same("nothing sold, nothing paid", 0.0, SellerRules.partialPrice(100.0, 0, 10));
        same("empty pile, nothing paid", 0.0, SellerRules.partialPrice(100.0, 5, 0));
    }

    private static void limits() {
        DailyLimits limits = new DailyLimits();
        UUID ana = UUID.randomUUID();
        UUID luis = UUID.randomUUID();
        String day = "2026-11-21";

        same("first pile fits", 64, limits.allowed(ana, "BLAZE_ROD", 100, 64, day));
        limits.add(ana, "BLAZE_ROD", 64, day);
        same("second pile fits in part", 36, limits.allowed(ana, "BLAZE_ROD", 100, 64, day));
        limits.add(ana, "BLAZE_ROD", 36, day);
        same("counted", 100, limits.sold(ana, "BLAZE_ROD", day));
        same("at the limit nothing more sells", 0, limits.allowed(ana, "BLAZE_ROD", 100, 64, day));
        same("not even one", 0, limits.allowed(ana, "BLAZE_ROD", 100, 1, day));

        same("another owner has their own count", 64, limits.allowed(luis, "BLAZE_ROD", 100, 64, day));
        same("another material is not touched", 64, limits.allowed(ana, "SLIME_BALL", 100, 64, day));
        same("a material without limit always sells", 64, limits.allowed(ana, "BONE", -1, 64, day));
        same("limit 0 never sells", 0, limits.allowed(luis, "MAGMA_CREAM", 0, 64, day));
        same("empty pile sells nothing", 0, limits.allowed(luis, "BLAZE_ROD", 100, 0, day));

        limits.add(ana, "BLAZE_ROD", 0, day);
        limits.add(ana, "BLAZE_ROD", -5, day);
        same("adding nothing changes nothing", 100, limits.sold(ana, "BLAZE_ROD", day));

        limits.add(luis, "BLAZE_ROD", Integer.MAX_VALUE, day);
        limits.add(luis, "BLAZE_ROD", 10, day);
        same("the count does not overflow", Integer.MAX_VALUE, limits.sold(luis, "BLAZE_ROD", day));
    }

    private static void days() {
        DailyLimits limits = new DailyLimits();
        UUID ana = UUID.randomUUID();
        limits.add(ana, "BLAZE_ROD", 100, "2026-11-21");
        limits.add(UUID.randomUUID(), "BLAZE_ROD", 5, "2026-11-21");
        same("two owners counted", 2, limits.entries());
        same("limit reached today", 0, limits.allowed(ana, "BLAZE_ROD", 100, 64, "2026-11-21"));
        same("the next day it sells again", 64, limits.allowed(ana, "BLAZE_ROD", 100, 64, "2026-11-22"));
        same("the old day is dropped, nothing piles up", 0, limits.entries());
        same("and the count starts from zero", 0, limits.sold(ana, "BLAZE_ROD", "2026-11-22"));
    }

    private static void persistence() throws Exception {
        File dir = Files.createTempDirectory("axminions-limits").toFile();
        File file = new File(dir, "seller-daily-limits.txt");
        UUID ana = UUID.randomUUID();
        String day = "2026-11-21";
        try {
            DailyLimits before = new DailyLimits();
            yes("nothing to save at the start", !before.isDirty());
            before.add(ana, "BLAZE_ROD", 70, day);
            yes("a sale marks it to save", before.isDirty());
            yes("saved", before.save(file));
            yes("saving clears the mark", !before.isDirty());
            yes("no temporary file is left", !new File(dir, "seller-daily-limits.txt.tmp").exists());

            DailyLimits after = new DailyLimits();
            after.load(file, day);
            same("the count survives a restart", 70, after.sold(ana, "BLAZE_ROD", day));
            same("and the limit goes on from there", 30, after.allowed(ana, "BLAZE_ROD", 100, 64, day));
            yes("loading does not mark it to save", !after.isDirty());

            DailyLimits nextDay = new DailyLimits();
            nextDay.load(file, "2026-11-22");
            same("a file of another day counts as nothing", 0, nextDay.sold(ana, "BLAZE_ROD", "2026-11-22"));

            DailyLimits missing = new DailyLimits();
            missing.load(new File(dir, "no-such-file.txt"), day);
            same("a missing file counts as nothing", 0, missing.sold(ana, "BLAZE_ROD", day));

            String broken = "day=" + day + "\n"
                    + ana + ";BLAZE_ROD;40\n"
                    + "not-a-uuid;BLAZE_ROD;9\n"
                    + ana + ";SLIME_BALL;many\n"
                    + ana + ";MAGMA_CREAM;-3\n"
                    + "garbage\n";
            Files.write(file.toPath(), broken.getBytes(StandardCharsets.UTF_8));
            DailyLimits repaired = new DailyLimits();
            repaired.load(file, day);
            same("the good line is read", 40, repaired.sold(ana, "BLAZE_ROD", day));
            same("broken lines are skipped", 1, repaired.entries());
        } finally {
            File[] left = dir.listFiles();
            if (left != null) {
                for (File f : left) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }
}
