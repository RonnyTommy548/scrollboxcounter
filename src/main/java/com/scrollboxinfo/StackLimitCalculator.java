package com.scrollboxinfo;

import net.runelite.api.Client;

import java.util.Arrays;
import java.util.List;

public class StackLimitCalculator
{
    public static final int SCROLL_CASE_BEGINNER_MINOR = 16565;
    public static final int SCROLL_CASE_BEGINNER_MAJOR = 16566;
    public static final int SCROLL_CASE_EASY_MINOR = 16567;
    public static final int SCROLL_CASE_EASY_MAJOR = 16586;
    public static final int SCROLL_CASE_MEDIUM_MINOR = 16587;
    public static final int SCROLL_CASE_MEDIUM_MAJOR = 16588;
    public static final int SCROLL_CASE_HARD_MINOR = 16589;
    public static final int SCROLL_CASE_HARD_MAJOR = 16590;
    public static final int SCROLL_CASE_ELITE_MINOR = 16591;
    public static final int SCROLL_CASE_ELITE_MAJOR = 16592;
    public static final int SCROLL_CASE_MASTER_MINOR = 16593;
    public static final int SCROLL_CASE_MASTER_MAJOR = 16594;
    public static final int SCROLL_CASE_MIMIC = 16595;

    public static final List<Integer> SCROLL_VARBITS = Arrays.asList(
        SCROLL_CASE_BEGINNER_MINOR,
        SCROLL_CASE_BEGINNER_MAJOR,
        SCROLL_CASE_EASY_MINOR,
        SCROLL_CASE_EASY_MAJOR,
        SCROLL_CASE_MEDIUM_MINOR,
        SCROLL_CASE_MEDIUM_MAJOR,
        SCROLL_CASE_HARD_MINOR,
        SCROLL_CASE_HARD_MAJOR,
        SCROLL_CASE_ELITE_MINOR,
        SCROLL_CASE_ELITE_MAJOR,
        SCROLL_CASE_MASTER_MINOR,
        SCROLL_CASE_MASTER_MAJOR,
        SCROLL_CASE_MIMIC
    );
    private static final int BASE_CLUE_CAP = 2;

    public static int getStackLimit(ClueTier tier, Client client)
    {
        int tierBonus = getTierBonus(tier, client);
        int mimicBonus = client.getVarbitValue(SCROLL_CASE_MIMIC);
        return BASE_CLUE_CAP + tierBonus + mimicBonus;
    }

    private static int getTierBonus(ClueTier tier, Client client)
    {
        switch (tier)
        {
            case BEGINNER:
                return client.getVarbitValue(SCROLL_CASE_BEGINNER_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_BEGINNER_MAJOR);
            case EASY:
                return client.getVarbitValue(SCROLL_CASE_EASY_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_EASY_MAJOR);
            case MEDIUM:
                return client.getVarbitValue(SCROLL_CASE_MEDIUM_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_MEDIUM_MAJOR);
            case HARD:
                return client.getVarbitValue(SCROLL_CASE_HARD_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_HARD_MAJOR);
            case ELITE:
                return client.getVarbitValue(SCROLL_CASE_ELITE_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_ELITE_MAJOR);
            case MASTER:
                return client.getVarbitValue(SCROLL_CASE_MASTER_MINOR)
                        + client.getVarbitValue(SCROLL_CASE_MASTER_MAJOR);
            default:
                return 0;
        }
    }
}