package com.bridge.mx;

/**
 * Maps (MX label state, parsed episode, MAL match) to a planned
 * my_list_status write. Pure computation — nothing is sent from here,
 * so Plan is always a dry-run.
 *
 *   new      -> planned (0 eps)
 *   watching -> watching, eps = current episode - 1 (eps already done)
 *   finished -> completed if current >= total; else watching with eps done
 */
public class Planner {

    public static void rebuild() {
        for (Store.Row r : Store.all()) {
            EpisodeParser.Parsed p = EpisodeParser.parse(r.title);
            if (!p.title.equals(r.parsedTitle)) {
                // source title changed -> stale match
                r.matchId = -1;
                r.matchTitle = "";
                r.numEp = -1;
                r.parsedTitle = p.title;
            }
            r.ep = p.ep;

            r.planStatus = "";
            r.planEps = -1;
            r.planText = "";

            int ep = r.ep;
            switch (r.state) {
                case "new":
                    r.planStatus = "planned";
                    r.planEps = 0;
                    r.planText = "\u2192 planned (0 eps)";
                    break;

                case "watching":
                    r.planStatus = "watching";
                    if (ep > 0) {
                        r.planEps = Math.max(0, ep - 1);
                        r.planText = "\u2192 watching, " + r.planEps
                                + " done (on ep " + ep + ")";
                    } else {
                        r.planText = "\u2192 watching";
                    }
                    break;

                case "finished":
                    if (ep > 0 && r.numEp > 0 && ep >= r.numEp) {
                        r.planStatus = "completed";
                        r.planEps = r.numEp;
                        r.planText = "\u2192 completed, " + r.numEp + "/" + r.numEp;
                    } else if (ep > 0) {
                        r.planStatus = "watching";
                        r.planEps = ep;
                        r.planText = "\u2192 watching, " + ep + "/"
                                + (r.numEp > 0 ? String.valueOf(r.numEp) : "?");
                    } else if (r.numEp > 1) {
                        r.planStatus = "watching";
                        r.planText = "\u2192 watching";
                    } else {
                        r.planStatus = "completed";
                        r.planText = "\u2192 completed";
                    }
                    break;

                default:
                    // unknown / none: no action
                    break;
            }
        }
    }
}
