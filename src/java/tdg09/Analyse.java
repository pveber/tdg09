package tdg09;

import com.beust.jcommander.JCommander;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;
import org.yaml.snakeyaml.representer.Representer;
import pal.alignment.Alignment;
import pal.statistics.LikelihoodRatioTest;
import pal.tree.Node;
import pal.tree.Tree;
import pal.tree.TreeUtils;
import tdg09.trees.TreeNodeLabeler;

import java.beans.IntrospectionException;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * The main entry point for tdg09 analysis.
 *
 * Author: Asif Tamuri (atamuri@ebi.ac.uk)
 * Date: 18/03/2013 16:31
 */
public class Analyse {
    Options options = new Options();

    public static void main(String[] args) {
        Analyse a = new Analyse();
        a.run(args);
    }

    private void run(String[] args) {
        // Parse the command-line options
        JCommander jc = new JCommander(options);
        try {
            jc.parse(args);
        } catch (Exception e) {
            jc.setProgramName("java -cp tdg09.jar tdg09.Analyse");
            jc.usage();
            System.exit(1);
        }

        // TODO: print output to console and also a file with the 'name' prefix, ala RAxML
        System.out.printf("StartTime: %s\n", new Timestamp(System.currentTimeMillis()));
        System.out.printf("WorkingDirectory: %s\n", System.getProperty("user.dir"));
        System.out.printf("Options: %s\n\n", Joiner.on(" ").join(Lists.newArrayList(args)));


        // Load the tree and alignment
        System.out.printf("TreeFile: %s\n", new File(options.treePath).getAbsolutePath());
        Tree tree = Utils.readTree(options.treePath);

        System.out.printf("AlignmentFile: %s\n\n", new File(options.alignmentPath).getAbsolutePath());
        Alignment alignment = Utils.readAlignment(options.alignmentPath);


        validate(tree, alignment);

        // Label the tree's internal nodes if they haven't already
        boolean labelled = true;
        for (int i = 0; i < tree.getInternalNodeCount(); i++) {
            Node node = tree.getInternalNode(i);
            if (!node.isRoot() && node.getIdentifier().getName().length() == 0) {
                labelled = false;
                break;
            }
        }

        if (!labelled) {
            System.out.println("# The internal nodes of the tree are not labelled. Labelling...");
            TreeNodeLabeler labeler = new TreeNodeLabeler();
            tree = labeler.label(tree);
        }

        // Output the labelling of internal nodes
        printGroupChanges(tree.getRoot());
        System.out.println();

        StringWriter sw = new StringWriter();
        TreeUtils.printNH(tree, new PrintWriter(sw));
        System.out.printf("LabelledTree: >\n  %s\n", sw.toString().replaceAll("\n", "\n  "));

        System.out.println("# " + Strings.repeat("-", 80) + "\n");

        // We're now ready to run
        ExecutorService pool = Executors.newFixedThreadPool(options.threads);
        CompletionService<Result> ecs = new ExecutorCompletionService<Result>(pool);

       for (int i = 1; i <= alignment.getSiteCount(); i++) {
            ecs.submit(new SiteAnalyser(alignment, tree, i, options.groups));
        }

        pool.shutdown();

        List<Result> results = Lists.newArrayList();
        try {
            while (!pool.isTerminated()) {
                Result r = ecs.take().get();
                results.add(r);
                System.out.printf("# %s - site %s complete.", new Timestamp(System.currentTimeMillis()), r.site);
                if (r.models != null) if (!r.models.get(0).converged || !r.models.get(1).converged)
                    System.out.print(" (warning: did not converge)");
                System.out.println();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println();
        System.out.println("# " + Strings.repeat("-", 80) + "\n");


        doLRT(results);
        System.out.println();

        fullResults(results);
        System.out.println();


        System.out.println("# " + Strings.repeat("-", 80) + "\n");

        Map<String, List<Result>> m = Maps.newHashMap();
        m.put("SiteResults", results);


        Yaml yaml = new Yaml(Result.getYamlRepresenter());
        System.out.println(yaml.dump(m));


        System.out.printf("EndTime: %s\n", new Timestamp(System.currentTimeMillis()));
        System.out.println();

        System.out.println("...");
    }

    private void fullResults(List<Result> results) {
        Collections.sort(results, Utils.doubleComparator("site", Result.class));
        DecimalFormat df = new DecimalFormat("###0.000000");

        System.out.println("FullResults:\n# Site, WAG+ssF params, WAG+ssF lnL, WAG+lssF params, WAG+lssF params, delta lnL, dof, LRT, FDR");
        for (Result r : results) {
            if (r.models != null) {
                System.out.printf("- [ %4d, %2d, %s, %2d, %s, %s, %2d, %.7f, %.7f ]%n",
                        r.site,
                        r.models.get(0).parameters,
                        Strings.padStart(df.format(r.models.get(0).lnL), 11, ' '),
                        r.models.get(1).parameters,
                        Strings.padStart(df.format(r.models.get(1).lnL), 11, ' '),
                        Strings.padStart(df.format(r.models.get(1).lnL - r.models.get(0).lnL), 10, ' '),
                        r.models.get(1).parameters - r.models.get(0).parameters,
                        r.lrt,
                        r.fdr);
            } else {
                System.out.printf("- [ %4d,  NA,         NA, NA,          NA,         NA, NA,        NA,        NA ]%n", r.site);
            }
        }

        System.out.println();
        System.out.println("# Misc.");
        List<Integer> sites = Lists.newArrayList();
        for (Result r : results) if (r.models == null) sites.add(r.site);
        System.out.println("ConservedPositions:");
        System.out.printf ("    Count: %s\n", sites.size());
        System.out.printf ("    Sites: %s\n", new Yaml().dump(sites));

    }

    private void doLRT(List<Result> results) {
        List<Result> polymorphicSites = Lists.newArrayList();

        for (Result r : results) {
            if (r.models != null) {
                polymorphicSites.add(r);

                r.lrt = LikelihoodRatioTest.getSignificance(
                        r.models.get(1).lnL - r.models.get(0).lnL, // delta lnL
                        r.models.get(1).parameters - r.models.get(0).parameters); // degrees of freedom
            } else {
                r.lrt = 1.0;
                r.fdr = 1.0;
            }
        }

        // order by likelihood ratio test p-value
        Collections.sort(polymorphicSites, Utils.doubleComparator("lrt", Result.class));

        // calculate false discovery rate (naive method)
        for (int i = 0; i < polymorphicSites.size(); i++) {
            int rank = i + 1;
            Result r = polymorphicSites.get(i);
            r.fdr = r.lrt * polymorphicSites.size() / rank;
        }

        // order by false discovery rate
        Collections.sort(polymorphicSites, Utils.doubleComparator("fdr", Result.class));

        DecimalFormat df = new DecimalFormat("##0.000000");

        System.out.println("LrtResults:\n#   Site,  delta lnL,  dof, LRT,       FDR");
        for (Result r : polymorphicSites) {
            System.out.printf("- [ %4d, %s,  %s,   %.7f, %.7f ]%n",
                    r.site,
                    Strings.padStart(df.format(r.models.get(1).lnL - r.models.get(0).lnL), 10, ' '),
                    r.models.get(1).parameters - r.models.get(0).parameters,
                    r.lrt,
                    r.fdr);
        }
    }

    private void validate(Tree tree, Alignment alignment) {
        // 1. Check the tree and alignment are in agreement
        if (!Utils.isTreeAndAlignmentValid(tree, alignment)) {
            System.out.println("ERROR: The tree and alignment do not have the same taxa.");
            System.exit(1);
        } else {
            System.out.printf("Alignment:\n  SequenceCount: %s\n  SiteCount: %s\n\n", alignment.getSequenceCount(), alignment.getSiteCount());
        }

        // 2. Check that all taxa have an assigned group and all groups are used
        List<String> taxa = Lists.newArrayList();
        Set<String> usedGroups = Sets.newHashSet();

        for (int i = 0; i < alignment.getSequenceCount(); i++) {
            taxa.add(alignment.getIdentifier(i).getName());
        }

        for (String group : options.groups) {
            Iterator<String> it = taxa.iterator();
            while (it.hasNext()) {
                if (it.next().startsWith(group)) {
                    it.remove();
                    usedGroups.add(group);
                }
            }
        }

        if (taxa.size() > 0) {
            System.out.printf("ERROR: %s taxa are not allocated to a group { %s }\n", taxa.size(), taxa.toString());
            System.exit(1);
        }

        Set<String> unusedGroups = Sets.difference(Sets.newHashSet(options.groups), usedGroups);
        if (unusedGroups.size() > 0) {
            System.out.printf("# WARNING: group(s) %s unused and will be ignored.\n", unusedGroups.toString());
            for (String s : unusedGroups) {
                options.groups.remove(s);
            }
        }

        System.out.printf("Groups: [%s]\n\n", Joiner.on(", ").join(options.groups));

    }

    private void printGroupChanges(Node node) {
        String nodeGroup;
        if (node.isRoot()) {
            System.out.printf("# Assuming that root of tree is in group [%s]\n", options.groups.get(0));
            nodeGroup = options.groups.get(0);
        } else {
            nodeGroup = getGroup(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i);
            String childGroup = getGroup(child);
            if (!nodeGroup.equals(childGroup)) {
                System.out.printf("# Switching from group [%s] to [%s] at branch %s..%s\n", nodeGroup, childGroup, node.getNumber(), child.getNumber());
            }
            printGroupChanges(child);
        }

    }

    private String getGroup(Node node) {
        for (String g : options.groups) {
            if (node.getIdentifier().getName().startsWith(g)) return g;
        }
        return null;
    }


}


