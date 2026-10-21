package com.editor;

import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.*;

/**
 * Add Service to Catalogue / Edit Service in Catalogue: describe a service from the place that needs it.
 *
 * What the designer knows is written to the process's domain catalogue straight away, as a draft, so the design
 * can go on. A draft is never deployed: Validate, Deploy and Build and Run say what it still needs. Once its
 * contract is complete and it is clear how it runs (a Java class, or a service called over REST) it becomes
 * active. For a Java class the editor can write starting code that keeps the contract and takes each route in
 * turn, so the process runs before the real logic exists.
 */
public final class ServiceDefinitionDialog extends JDialog {
    private final Canvas canvas;
    private final ProcessElement place;
    private final ServiceRegistry.Contract existing;
    private final File catalogue, common;
    private final String domain;

    // Each empty field shows, greyed, an example of what goes in it and in what form.
    private final JTextField service = hinted("e.g. FraudScreenService"), operation = hinted("e.g. screen"),
        returns = hinted("In words, e.g. a screening result for the application"),
        inputs = hinted("Names, separated by commas, e.g. token"), returnAttribute = hinted("A name, e.g. token"),
        routes = hinted("Values, separated by commas, e.g. clear, review"), javaClass = new JTextField(24);
    private final JTextArea description = new HintTextArea("In words, e.g. Screens an application for signs of fraud.");
    private final JRadioButton undecided = new JRadioButton("Not decided yet: it stays a draft"),
        java = new JRadioButton("A Java class:"), rest = new JRadioButton("A service called over REST, in any language (endpoint set in Deploy)");
    private final JCheckBox startingCode = new JCheckBox("Write starting code for the class if it does not exist", true);
    private final JLabel outcome = new JLabel(" ");
    /** Other processes that use the service being edited; its contract is then fixed here. */
    private final List<String> usedElsewhere;
    private boolean saved;

    private ServiceDefinitionDialog(Window owner, Canvas canvas, ProcessElement place, ServiceRegistry.Contract existing) {
        super(owner, existing == null ? "Add Service to Catalogue" : "Edit Service in Catalogue", ModalityType.APPLICATION_MODAL);
        this.canvas = canvas; this.place = place; this.existing = existing;
        ServiceRegistry registry = canvas.getServiceRegistry();
        catalogue = registry.catalogueFile(); common = registry.common();
        domain = catalogue.getParentFile().getName();

        usedElsewhere = existing == null ? new ArrayList<>() : registry.usedElsewhere(existing.service, canvas.getDefinitionLocation());
        CatalogueWriter.Entry start = existing != null ? CatalogueWriter.from(existing) : suggestion(canvas, place);
        service.setText(start.service); operation.setText(start.operation); description.setText(start.description);
        returns.setText(start.returns); inputs.setText(String.join(", ", start.inputs)); returnAttribute.setText(start.returnAttribute);
        routes.setText(String.join(", ", start.routes));
        javaClass.setText(!start.implementationClass.isEmpty() ? start.implementationClass : ServiceStub.className(domain, start.service.isEmpty() ? "NewService" : start.service));
        ButtonGroup how = new ButtonGroup(); how.add(undecided); how.add(java); how.add(rest);
        // A new service gets runnable starting code by default, so the design can go straight through to a run.
        // An existing one shows how it runs now: its Java class, REST (finished without a class), or undecided (a draft).
        (existing == null || !existing.implementationClass.isEmpty() ? java : existing.draft ? undecided : rest).setSelected(true);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        String introText = existing == null
            ? "It is written to <b>" + catalogue.getName() + "</b> now, as a draft, so you can keep designing. Leave out what you do not know yet: "
                + "the editor tells you what is still needed before the place can be deployed."
            : usedElsewhere.isEmpty()
            ? "<b>" + existing.service + "</b> in <b>" + catalogue.getName() + "</b>" + (existing.draft ? ", a draft." : ".") + " Changes are saved to the catalogue"
                + " and, while its class is still the editor's starting code, to the code too."
            : "<b>" + existing.service + "</b> in <b>" + catalogue.getName() + "</b> is also used by " + escape(String.join(", ", usedElsewhere))
                + ", so its name, operation, inputs, return attribute and how it runs are fixed here. Its description and routes can still be changed.";
        JLabel intro = new JLabel("<html><div style='width:430px'>" + introText + "</div></html>");
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; form.add(intro, c); c.gridwidth = 1;
        row(form, c, 1, "Service:", service, "A Java-style name, e.g. FraudScreenService.");
        row(form, c, 2, "Operation:", operation, "What the place asks the service to do, e.g. screen.");
        description.setLineWrap(true); description.setWrapStyleWord(true);
        row(form, c, 3, "What it does:", new JScrollPane(description), "Shown in the editor's What it does box.");
        row(form, c, 4, "What it returns:", returns, "What the result is, in words. The values it routes on go in Routes on, not here.");
        row(form, c, 5, "Inputs:", inputs, "Names separated by commas, in the order of the method's parameters.");
        row(form, c, 6, "Return attribute:", returnAttribute, "The name the result is published under. The next place takes it as input, so it must be the name that place's input has.");
        row(form, c, 7, "Routes on:", routes, "The values its result routes on, separated by commas; the guards leaving the place test these. Leave blank if the place has one way out.");
        JPanel howPanel = new JPanel(new GridBagLayout());
        howPanel.setBorder(BorderFactory.createTitledBorder("How it runs"));
        GridBagConstraints h = new GridBagConstraints(); h.anchor = GridBagConstraints.WEST; h.fill = GridBagConstraints.HORIZONTAL; h.gridx = 0; h.gridwidth = 2;
        h.gridy = 0; howPanel.add(undecided, h);
        h.gridy = 1; h.gridwidth = 1; howPanel.add(java, h); h.gridx = 1; h.weightx = 1; howPanel.add(javaClass, h);
        h.gridx = 0; h.weightx = 0; h.gridwidth = 2; h.gridy = 2; h.insets = new Insets(0, 24, 0, 0); howPanel.add(startingCode, h);
        h.insets = new Insets(0, 0, 0, 0); h.gridy = 3; howPanel.add(rest, h);
        c.gridx = 0; c.gridy = 8; c.gridwidth = 2; form.add(howPanel, c);
        c.gridy = 9; form.add(outcome, c);

        JButton ok = new JButton(existing == null ? "Add to " + catalogue.getName() : "Save to " + catalogue.getName()), cancel = new JButton("Cancel");
        ok.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(ok); buttons.add(cancel);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        Runnable update = this::updateOutcome;
        for (JTextField field : Arrays.asList(service, operation, returns, inputs, returnAttribute, routes, javaClass))
            field.getDocument().addDocumentListener(listener(update));
        description.getDocument().addDocumentListener(listener(update));
        service.getDocument().addDocumentListener(listener(() -> {   // keep the suggested class name in step with the service name
            if (javaClass.getText().startsWith(ServiceStub.className(domain, "")) && ServiceStub.identifier(service.getText().trim()))
                javaClass.setText(ServiceStub.className(domain, service.getText().trim()));
        }));
        for (AbstractButton b : Arrays.asList(undecided, java, rest, startingCode)) b.addActionListener(e -> updateOutcome());
        if (!usedElsewhere.isEmpty())
            for (JComponent fixed : Arrays.<JComponent>asList(service, operation, inputs, returnAttribute, javaClass, undecided, java, rest, startingCode)) fixed.setEnabled(false);
        updateOutcome();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the form for a place: edit shows the place's own service (draft or finished) filled in; otherwise it adds a
     * new service. Returns true when the catalogue was written and the place now uses the service.
     */
    public static boolean open(Component parent, Canvas canvas, ProcessElement place, boolean edit) {
        ServiceRegistry registry = canvas.getServiceRegistry();
        // The first service of a process needs its domain's catalogue: choose one, or create it for a new domain.
        if (registry.problem() == null && !registry.hasDomainCatalogue() && !canvas.chooseCatalogue(parent, CatalogueChoice.BEFORE_WE_BEGIN)) return false;
        String refusal = refusal(registry);
        if (refusal != null) { JOptionPane.showMessageDialog(parent, refusal, "Add Service to Catalogue", JOptionPane.WARNING_MESSAGE); return false; }
        ServiceRegistry.Contract own = edit ? registry.domainContract(place) : null;
        ServiceDefinitionDialog dialog = new ServiceDefinitionDialog(SwingUtilities.getWindowAncestor(parent), canvas, place, own);
        dialog.setVisible(true);
        return dialog.saved;
    }

    /** Why a service cannot be added from this process, or null. */
    static String refusal(ServiceRegistry registry) {
        if (registry.problem() != null || registry.catalogueFile() == null) return "Choose this process's catalogue first: " + registry.problem();
        if (registry.catalogueFile().getPath().replace(File.separatorChar, '/').endsWith(ServiceRegistry.STANDARD_CATALOGUE))
            return "The standard catalogue is shared by every process, so services are not added to it. Choose or create your domain's catalogue first.";
        return null;
    }

    /**
     * For a new service: a name from the place's label, and input and result names that match the neighbouring
     * places, since a place takes as input what the place before it publishes.
     */
    private static CatalogueWriter.Entry suggestion(Canvas canvas, ProcessElement place) {
        CatalogueWriter.Entry e = new CatalogueWriter.Entry();
        String label = place.getLabel() == null ? "" : place.getLabel().replaceAll("[^A-Za-z0-9_]", "");
        if (!label.isEmpty() && Character.isLetter(label.charAt(0))) {
            String base = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            e.service = base.endsWith("Service") ? base : base + "Service";
            // A name another domain already uses is taken: prefix this domain's (Triage -> ClaimsTriageService).
            ServiceRegistry registry = canvas.getServiceRegistry();
            if (registry.catalogueFile() != null && ServiceRegistry.definedElsewhere(registry.common(), e.service, registry.catalogueFile()) != null)
                e.service = domainName(registry.catalogueDomain()) + e.service;
        }
        java.util.Set<String> before = canvas.neighbourNames(place, false), after = canvas.neighbourNames(place, true);
        e.inputs.add(before.size() == 1 ? before.iterator().next() : "token");
        e.returnAttribute = after.size() == 1 ? after.iterator().next() : "token";
        return e;
    }

    /** claims -> Claims, home_loans -> HomeLoans */
    static String domainName(String domain) {
        return CatalogueWriter.catalogueName(domain == null || domain.isEmpty() ? "x" : domain).replaceFirst("\\.json$", "");
    }

    // ------------------------------------------------------------------ what OK will do

    private CatalogueWriter.Entry entry() {
        CatalogueWriter.Entry e = new CatalogueWriter.Entry();
        e.service = service.getText().trim(); e.operation = operation.getText().trim(); e.description = description.getText().trim();
        e.returns = returns.getText().trim(); e.returnAttribute = returnAttribute.getText().trim();
        e.inputs.addAll(list(inputs.getText())); e.routes.addAll(list(routes.getText()));
        if (java.isSelected()) e.implementationClass = javaClass.getText().trim();
        return e;
    }

    /** Problems that stop the entry being written at all. */
    private List<String> errors(CatalogueWriter.Entry e) {
        List<String> errors = new ArrayList<>();
        if (!ServiceStub.identifier(e.service)) errors.add("The service needs a name of letters, digits and _, starting with a letter.");
        if (!ServiceStub.identifier(e.operation)) errors.add("The operation needs a name of letters, digits and _, starting with a letter.");
        for (String input : e.inputs) if (!ServiceStub.identifier(input)) errors.add("Input \"" + input + "\" is not a valid name.");
        if (!e.returnAttribute.isEmpty() && !ServiceStub.identifier(e.returnAttribute)) errors.add("The return attribute is not a valid name.");
        if (java.isSelected() && !e.implementationClass.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
            errors.add("The Java class needs a full name, e.g. " + ServiceStub.className(domain, "FraudScreenService") + ".");
        ServiceRegistry registry = canvas.getServiceRegistry();
        boolean renamed = existing == null || !existing.service.equals(e.service) || !existing.operation.equals(e.operation);
        if (renamed && registry.contract(e.service, e.operation) != null)
            errors.add(e.service + "." + e.operation + " is already in the catalogue" + (registry.isStandard(e.service) ? " as a standard service" : "") + ".");
        else if (registry.isStandard(e.service)) errors.add(e.service + " is a standard service; choose another name.");
        else if (existing == null || !existing.service.equals(e.service)) {
            File other = ServiceRegistry.definedElsewhere(common, e.service, catalogue);
            if (other != null) errors.add(e.service + " is already a service in " + other.getName() + " (" + other.getParentFile().getName()
                + "); every domain's services are packaged together, so choose another name, e.g. " + domainName(domain) + e.service + ".");
        }
        return errors;
    }

    /** What the service still needs before it can be deployed (empty: it becomes active). */
    private List<String> stillNeeded(CatalogueWriter.Entry e) {
        List<String> needed = new ArrayList<>();
        if (e.description.isEmpty()) needed.add("a description");
        if (e.returns.isEmpty()) needed.add("what it returns");
        if (e.inputs.isEmpty()) needed.add("its inputs");
        if (e.returnAttribute.isEmpty()) needed.add("a return attribute");
        if (undecided.isSelected()) needed.add("its code: a Java class, or a service called over REST");
        else if (java.isSelected() && !e.implementationClass.isEmpty() && !ServiceStub.sourceFile(common, e.implementationClass).isFile() && !startingCode.isSelected())
            needed.add("its Java class, " + relative(ServiceStub.sourceFile(common, e.implementationClass)));
        return needed;
    }

    private void updateOutcome() {
        startingCode.setEnabled(java.isSelected()); javaClass.setEnabled(java.isSelected());
        CatalogueWriter.Entry e = entry();
        List<String> errors = errors(e), needed = stillNeeded(e);
        // Values typed into What it returns instead of Routes on would leave the starting code and guard checks without them.
        if (e.routes.isEmpty() && e.returns.matches("[A-Za-z0-9_]+(\\s*,\\s*[A-Za-z0-9_]+)+")) {
            outcome.setText("<html><div style='width:430px'><font color='#B00020'>What it returns looks like routing values. Put them in "
                + "<b>Routes on</b>, which the guards and the starting code use, and describe the result in words here.</font></div></html>");
            return;
        }
        String text = e.service.isEmpty() || e.operation.isEmpty() ? "Name the service and its operation; everything else can follow later."
            : !errors.isEmpty() ? "<font color='#B00020'>" + escape(errors.get(0)) + "</font>"
            : needed.isEmpty() ? "<b>Ready:</b> it will be an active service that can be deployed."
            : "<b>Saved as a draft.</b> Before it can be deployed it still needs " + escape(String.join(", ", needed)) + ".";
        outcome.setText("<html><div style='width:430px'>" + text + "</div></html>");
    }

    private void save() {
        CatalogueWriter.Entry e = entry();
        List<String> errors = errors(e);
        if (!errors.isEmpty()) { JOptionPane.showMessageDialog(this, String.join("\n", errors), getTitle(), JOptionPane.WARNING_MESSAGE); return; }
        List<String> needed = stillNeeded(e);
        e.draft = !needed.isEmpty();
        File source = e.implementationClass.isEmpty() ? null : ServiceStub.sourceFile(common, e.implementationClass);
        boolean wroteCode;
        try {
            wroteCode = write(catalogue, common, existing, e, startingCode.isSelected());
            canvas.getServiceRegistry().reload();
            canvas.useService(place, e.service, e.operation);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not write " + catalogue.getName() + ":\n" + ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        saved = true;
        dispose();
        JOptionPane.showMessageDialog(getOwner(), next(e, needed, source, wroteCode), e.service + (e.draft ? " is a draft" : " is ready"), JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Writes the entry (new, or replacing existing) to the catalogue, and the class's code when it is starting code:
     * new starting code if asked for and the class does not exist, or the editor's starting code brought in line with
     * the edited contract and routes. Code the designer has written (no marker) is never touched. Returns true when
     * code was written.
     */
    static boolean write(File catalogue, File common, ServiceRegistry.Contract existing, CatalogueWriter.Entry e, boolean writeStartingCode) throws Exception {
        File source = e.implementationClass.isEmpty() ? null : ServiceStub.sourceFile(common, e.implementationClass);
        boolean wroteCode = false;
        boolean starting = source != null && ServiceStub.isStartingCode(common, e.implementationClass);
        if (!e.draft && source != null && ((!source.isFile() && writeStartingCode) || starting)) {
            Files.createDirectories(source.getParentFile().toPath());
            Files.write(source.toPath(), ServiceStub.source(e.implementationClass, e).getBytes(StandardCharsets.UTF_8));
            wroteCode = true;
        }
        if (existing == null) CatalogueWriter.add(catalogue, e);
        else CatalogueWriter.update(catalogue, existing.service, existing.operation, e, true);
        return wroteCode;
    }

    /** What the designer does next: finish the draft, write the real logic, or write and point at the REST service. */
    private String next(CatalogueWriter.Entry e, List<String> needed, File source, boolean wroteCode) {
        String where = e.service + (existing == null ? " is in " : " is updated in ") + catalogue.getName() + " and the place uses it.\n\n";
        if (e.draft) return where + "It is a draft, so you can keep designing, but it cannot be deployed until it has "
            + String.join(", ", needed) + ".\nRight-click the place and choose Edit Service in Catalogue... to finish it.";
        String packaging = packagingNote();
        if (source != null) return where + (wroteCode
                ? "Remember to write its code. The starting class is in " + relative(source) + ":\nit keeps the contract and returns "
                    + (e.routes.isEmpty() ? "true" : "the routes in turn") + ", so you can deploy and run the process now.\n"
                    + "Replace the body of " + e.operation + " with the real logic; until then Validate reminds you."
                : "Its code is " + relative(source) + "; make sure it has " + e.operation + "(" + String.join(", ", e.inputs) + "), returning "
                    + e.returnAttribute + (e.routes.isEmpty() ? "" : " routed on " + String.join(", ", e.routes)) + ".")
            + packaging;
        return where + "Remember to write the service. It can be written in any language and run anywhere it can be reached over HTTP; "
            + "ServiceTutorial.md section 8 shows one in Node.js.\nThen, in Deploy, set this place's adapter to rest and give its endpoint.";
    }

    /** The build packages Java services only from catalogues listed in btsn.services/packaging.json. */
    private String packagingNote() {
        try {
            File packaging = new File(common.getParentFile(), "btsn.services/packaging.json");
            String listed = new String(Files.readAllBytes(packaging.toPath()), StandardCharsets.UTF_8);
            if (listed.contains("BusinessServiceDefinitions/" + domain + "/" + catalogue.getName())) return "";
            return "\n\nNote: btsn.services/packaging.json does not list " + catalogue.getName()
                + ", so the build will not package its classes. Add \"../btsn.common/BusinessServiceDefinitions/" + domain + "/" + catalogue.getName() + "\" to its catalogues.";
        } catch (Exception ex) { return ""; }
    }

    // ------------------------------------------------------------------ small helpers

    private static JTextField hinted(String hint) {
        return new JTextField(24) {
            @Override protected void paintComponent(Graphics g) { super.paintComponent(g); paintHint(this, g, hint); }
        };
    }
    private static final class HintTextArea extends JTextArea {
        private final String hint;
        HintTextArea(String hint) { super(3, 24); this.hint = hint; }
        @Override protected void paintComponent(Graphics g) { super.paintComponent(g); paintHint(this, g, hint); }
    }
    /** The greyed example, shown while the field is empty. */
    private static void paintHint(javax.swing.text.JTextComponent field, Graphics g, String hint) {
        if (!field.getText().isEmpty() || !field.isEnabled()) return;
        Graphics2D g2 = (Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Color.GRAY);
        g2.setFont(field.getFont().deriveFont(Font.ITALIC));
        Insets in = field.getInsets();
        g2.drawString(hint, in.left + 2, in.top + g2.getFontMetrics().getAscent());
        g2.dispose();
    }

    private static void row(JPanel form, GridBagConstraints c, int y, String label, JComponent field, String tip) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; form.add(field, c);
        field.setToolTipText(tip);
    }
    private static List<String> list(String text) {
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) if (!part.trim().isEmpty()) values.add(part.trim());
        return values;
    }
    private String relative(File file) {
        try { return common.getParentFile().toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); }
        catch (Exception ex) { return file.getPath(); }
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static javax.swing.event.DocumentListener listener(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
        };
    }
}
