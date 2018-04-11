package som.langserv;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.services.LanguageClient;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.vm.PolyglotEngine;
import com.oracle.truffle.api.vm.PolyglotEngine.Builder;

import bd.basic.ProgramDefinitionError;
import som.VM;
import som.compiler.MixinDefinition;
import som.compiler.MixinDefinition.SlotDefinition;
import som.compiler.Parser.ParseError;
import som.compiler.SemanticDefinitionError;
import som.compiler.SourcecodeCompiler;
import som.interpreter.SomLanguage;
import som.interpreter.nodes.ArgumentReadNode.LocalArgumentReadNode;
import som.interpreter.nodes.ArgumentReadNode.NonLocalArgumentReadNode;
import som.interpreter.nodes.ExpressionNode;
import som.interpreter.nodes.LocalVariableNode;
import som.interpreter.nodes.NonLocalVariableNode;
import som.interpreter.nodes.dispatch.Dispatchable;
import som.vm.Primitives;
import som.vm.VmOptions;
import som.vmobjects.SInvokable;
import som.vmobjects.SSymbol;
import tools.Send;
import tools.SourceCoordinate;
import tools.language.StructuralProbe;


public class SomAdapter {

  public final static String FILE_ENDING = ".ns";

  public final static String CORE_LIB_PATH = System.getProperty("som.langserv.core-lib");

  private final Map<String, SomStructures> structuralProbes;
  private final SomCompiler                compiler;

  private LanguageClient client;

  public SomAdapter() {
    VM vm = initializePolyglot();
    this.compiler = new SomCompiler(vm.getLanguage());
    this.structuralProbes = new HashMap<>();
    registerVmMirrorPrimitives(vm);
  }

  private void registerVmMirrorPrimitives(final VM vm) {
    Primitives prims = new Primitives(vm.getLanguage());

    @SuppressWarnings({"rawtypes", "unchecked"})
    HashMap<SSymbol, SInvokable> ps = (HashMap) prims.takeVmMirrorPrimitives();

    SomStructures primProbe =
        new SomStructures(Source.newBuilder("vmMirror").mimeType(SomLanguage.MIME_TYPE)
                                .name("vmMirror").build());
    for (SInvokable i : ps.values()) {
      primProbe.recordNewMethod(i);
    }

    structuralProbes.put("vmMirror", primProbe);
  }

  public void connect(final LanguageClient client) {
    this.client = client;
  }

  private VM initializePolyglot() {
    String coreLib = CORE_LIB_PATH;
    if (coreLib == null) {
      throw new IllegalArgumentException(
          "The som.langserv.core-lib system property needs to be set. For instance: -Dsom.langserv.core-lib=/SOMns/core-lib");
    }

    String[] args = new String[] {"--kernel", coreLib + "/Kernel.ns",
        "--platform", coreLib + "/Platform.ns"};
    VmOptions vmOptions = new VmOptions(args);
    VM vm = new VM(vmOptions);
    Builder builder = PolyglotEngine.newBuilder();
    builder.config(SomLanguage.MIME_TYPE, SomLanguage.VM_OBJECT, vm);

    PolyglotEngine engine = builder.build();
    engine.getRuntime().getInstruments().values().forEach(i -> i.setEnabled(false));

    // Trigger object system initialization
    engine.getLanguages().get(SomLanguage.MIME_TYPE).getGlobalObject();

    return vm;
  }

  public void loadWorkspace(final String uri) throws URISyntaxException {
    if (uri == null) {
      return;
    }

    URI workspaceUri = new URI(uri);
    File workspace = new File(workspaceUri);
    assert workspace.isDirectory();

    new Thread(() -> loadWorkspaceAndLint(workspace)).start();
  }

  private void loadWorkspaceAndLint(final File workspace) {
    Map<String, List<Diagnostic>> allDiagnostics = new HashMap<>();
    loadFolder(workspace, allDiagnostics);

    for (Entry<String, List<Diagnostic>> e : allDiagnostics.entrySet()) {
      try {
        lintSends(e.getKey(), e.getValue());
      } catch (URISyntaxException ex) {
        /*
         * at this point, there is nothing to be done anymore,
         * would have been problematic earlier
         */
      }

      reportDiagnostics(e.getValue(), e.getKey());
    }
  }

  private void loadFolder(final File folder,
      final Map<String, List<Diagnostic>> allDiagnostics) {
    for (File f : folder.listFiles()) {
      if (f.isDirectory()) {
        loadFolder(f, allDiagnostics);
      } else if (f.getName().endsWith(FILE_ENDING)) {
        try {
          byte[] content = Files.readAllBytes(f.toPath());
          String str = new String(content, StandardCharsets.UTF_8);
          String uri = f.toURI().toString();
          List<Diagnostic> diagnostics = parse(str, uri);
          allDiagnostics.put(uri, diagnostics);
        } catch (IOException | URISyntaxException e) {
          // if loading fails, we don't do anything, just move on to the next file
        }
      }
    }
  }

  public void lintSends(final String docUri, final List<Diagnostic> diagnostics)
      throws URISyntaxException {
    SomStructures probe;
    synchronized (structuralProbes) {
      probe = structuralProbes.get(docUriToNormalizedPath(docUri));
    }
    SomLint.checkSends(structuralProbes, probe, diagnostics);
  }

  public static String docUriToNormalizedPath(final String documentUri)
      throws URISyntaxException {
    URI uri = new URI(documentUri).normalize();
    return uri.getPath();
  }

  private SomStructures getProbe(final String documentUri) {
    synchronized (structuralProbes) {
      try {
        return structuralProbes.get(docUriToNormalizedPath(documentUri));
      } catch (URISyntaxException e) {
        return null;
      }
    }
  }

  /** Create a copy to work on safely. */
  private Collection<SomStructures> getProbes() {
    synchronized (structuralProbes) {
      return new ArrayList<>(structuralProbes.values());
    }
  }

  public List<Diagnostic> parse(final String text, final String sourceUri)
      throws URISyntaxException {
    String path = docUriToNormalizedPath(sourceUri);
    Source source = Source.newBuilder(text).name(path).mimeType(SomLanguage.MIME_TYPE)
                          .uri(new URI(sourceUri).normalize()).build();

    SomStructures newProbe = new SomStructures(source);
    List<Diagnostic> diagnostics = newProbe.getDiagnostics();
    try {
      // clean out old structural data
      synchronized (structuralProbes) {
        structuralProbes.remove(path);
      }
      synchronized (newProbe) {
        MixinDefinition def = compiler.compileModule(source, newProbe);
        SomLint.checkModuleName(path, def, diagnostics);
      }
    } catch (ParseError e) {
      return toDiagnostics(e, diagnostics);
    } catch (SemanticDefinitionError e) {
      return toDiagnostics(e, diagnostics);
    } catch (Throwable e) {
      return toDiagnostics(e.getMessage(), diagnostics);
    } finally {
      // set new probe once done with everything
      synchronized (structuralProbes) {
        structuralProbes.put(path, newProbe);
      }
    }
    return diagnostics;
  }

  private List<Diagnostic> toDiagnostics(final ParseError e,
      final List<Diagnostic> diagnostics) {
    Diagnostic d = new Diagnostic();
    d.setSeverity(DiagnosticSeverity.Error);

    SourceCoordinate coord = e.getSourceCoordinate();
    d.setRange(toRangeMax(coord));
    d.setMessage(e.getMessage());
    d.setSource("Parser");

    diagnostics.add(d);
    return diagnostics;
  }

  private List<Diagnostic> toDiagnostics(final SemanticDefinitionError e,
      final List<Diagnostic> diagnostics) {
    SourceSection source = e.getSourceSection();

    Diagnostic d = new Diagnostic();
    d.setSeverity(DiagnosticSeverity.Error);
    d.setRange(toRange(source));
    d.setMessage(e.getMessage());
    d.setSource("Parser");

    diagnostics.add(d);
    return diagnostics;
  }

  private List<Diagnostic> toDiagnostics(final String msg,
      final List<Diagnostic> diagnostics) {
    Diagnostic d = new Diagnostic();
    d.setSeverity(DiagnosticSeverity.Error);

    d.setMessage(msg);
    d.setSource("Parser");

    diagnostics.add(d);
    return diagnostics;
  }

  public static Position pos(final int startLine, final int startChar) {
    Position pos = new Position();
    pos.setLine(startLine - 1);
    pos.setCharacter(startChar - 1);
    return pos;
  }

  @SuppressWarnings("unused")
  private static boolean in(final SourceSection s, final int line, final int character) {
    if (s.getStartLine() > line || s.getEndLine() < line) {
      return false;
    }

    if (s.getStartLine() == line && s.getStartColumn() > character) {
      return false;
    }
    if (s.getEndLine() == line && s.getEndColumn() < character) {
      return false;
    }

    return true;
  }

  public DocumentHighlight getHighlight(final String documentUri,
      final int line, final int character) {
    // TODO: this is wrong, it should be something entierly different.
    // this feature is about marking the occurrences of a selected element
    // like a variable, where it is used.
    // so, this should actually return multiple results.
    // The spec is currently broken for that.

    // XXX: the code here doesn't make any sense for what it is supposed to do

    // Map<SourceSection, Set<Class<? extends Tags>>> sections = Highlight.
    // getSourceSections();
    // SourceSection[] all = sections.entrySet().stream().map(e -> e.getKey()).toArray(size ->
    // new SourceSection[size]);
    //
    // Stream<Entry<SourceSection, Set<Class<? extends Tags>>>> filtered = sections.
    // entrySet().stream().filter(
    // (final Entry<SourceSection, Set<Class<? extends Tags>>> e) -> in(e.getKey(), line,
    // character));
    //
    // @SuppressWarnings("rawtypes")
    // Entry[] matching = filtered.toArray(size -> new Entry[size]);
    //
    // for (Entry<SourceSection, Set<Class<? extends Tags>>> e : matching) {
    // int kind;
    // if (e.getValue().contains(LiteralTag.class)) {
    // kind = DocumentHighlight.KIND_READ;
    // } else {
    // kind = DocumentHighlight.KIND_TEXT;
    // }
    // DocumentHighlightImpl highlight = new DocumentHighlightImpl();
    // highlight.setKind(kind);
    // highlight.setRange(getRange(e.getKey()));
    // return highlight;
    // }
    //
    // DocumentHighlightImpl highlight = new DocumentHighlightImpl();
    // highlight.setKind(DocumentHighlight.KIND_TEXT);
    // RangeImpl range = new RangeImpl();
    // range.setStart(pos(line, character));
    // range.setEnd(pos(line, character + 1));
    // highlight.setRange(range);
    // return highlight;
    return null;
  }

  public static Range toRange(final SourceSection ss) {
    Range range = new Range();
    range.setStart(pos(ss.getStartLine(), ss.getStartColumn()));
    range.setEnd(pos(ss.getEndLine(), ss.getEndColumn() + 1));
    return range;
  }

  public static Range toRangeMax(final SourceCoordinate coord) {
    Range range = new Range();
    range.setStart(pos(coord.startLine, coord.startColumn));
    range.setEnd(pos(coord.startLine, Integer.MAX_VALUE));
    return range;
  }

  public static Location getLocation(final SourceSection ss) {
    Location loc = new Location();
    loc.setUri(ss.getSource().getURI().toString());
    loc.setRange(toRange(ss));
    return loc;
  }

  public List<? extends SymbolInformation> getSymbolInfo(final String documentUri) {
    SomStructures probe = getProbe(documentUri);
    ArrayList<SymbolInformation> results = new ArrayList<>();
    if (probe == null) {
      return results;
    }

    addAllSymbols(results, null, probe, documentUri);
    return results;
  }

  public List<? extends SymbolInformation> getAllSymbolInfo(final String query) {
    Collection<SomStructures> probes = getProbes();

    ArrayList<SymbolInformation> results = new ArrayList<>();

    for (SomStructures probe : probes) {
      addAllSymbols(results, query, probe, probe.getDocumentUri());
    }

    return results;
  }

  private void addAllSymbols(final ArrayList<SymbolInformation> results, final String query,
      final SomStructures probe, final String documentUri) {
    synchronized (probe) {
      Set<MixinDefinition> classes = probe.getClasses();
      for (MixinDefinition m : classes) {
        assert sameDocument(documentUri, m.getSourceSection());
        addSymbolInfo(m, query, results);
      }

      Set<SInvokable> methods = probe.getMethods();
      for (SInvokable m : methods) {
        assert sameDocument(documentUri, m.getSourceSection());

        if (matchQuery(query, m)) {
          results.add(getSymbolInfo(m));
        }
      }
    }
  }

  private boolean sameDocument(final String documentUri, final SourceSection ss) {
    if (documentUri == null) {
      return ss == null;
    }

    if (ss == null) {
      return documentUri.endsWith("vmMirror");
    }

    try {
      return ss.getSource().getURI().getPath().equals(new URI(documentUri).getPath());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static boolean matchQuery(final String query, final SInvokable m) {
    return SomStructures.fuzzyMatches(m.getSignature().getString(), query);
  }

  private static boolean matchQuery(final String query, final MixinDefinition m) {
    return SomStructures.fuzzyMatches(m.getName().getString(), query);
  }

  private static boolean matchQuery(final String query, final SlotDefinition s) {
    return SomStructures.fuzzyMatches(s.getName().getString(), query);
  }

  private static SymbolInformation getSymbolInfo(final SInvokable m) {
    SymbolInformation sym = new SymbolInformation();
    sym.setName(m.getSignature().toString());
    sym.setKind(SymbolKind.Method);
    if (null != m.getSourceSection()) {
      sym.setLocation(getLocation(m.getSourceSection()));
    }
    if (m.getHolderUnsafe() != null) {
      sym.setContainerName(m.getHolder().getName().getString());
    }
    return sym;
  }

  private static void addSymbolInfo(final MixinDefinition m, final String query,
      final ArrayList<SymbolInformation> results) {
    if (matchQuery(query, m)) {
      results.add(getSymbolInfo(m));
    }

    // We add the slots here, because we have more context at this point
    for (Dispatchable d : m.getInstanceDispatchables().values()) {
      // needs to be exact test to avoid duplicate info
      if (d.getClass() == SlotDefinition.class) {
        if (matchQuery(query, (SlotDefinition) d)) {
          results.add(getSymbolInfo((SlotDefinition) d, m));
        }
      }
    }
  }

  private static SymbolInformation getSymbolInfo(final SlotDefinition d,
      final MixinDefinition m) {
    SymbolInformation sym = new SymbolInformation();
    sym.setName(d.getName().getString());
    SymbolKind kind = m.isModule() ? SymbolKind.Constant
        : SymbolKind.Property;
    sym.setKind(kind);
    sym.setLocation(getLocation(d.getSourceSection()));
    sym.setContainerName(m.getName().getString());
    return sym;
  }

  private static SymbolInformation getSymbolInfo(final MixinDefinition m) {
    SymbolInformation sym = new SymbolInformation();
    sym.setName(m.getName().getString());
    SymbolKind kind = m.isModule() ? SymbolKind.Module
        : SymbolKind.Class;
    sym.setKind(kind);
    sym.setLocation(getLocation(m.getSourceSection()));

    MixinDefinition outer = m.getOuterMixinDefinition();
    if (outer != null) {
      sym.setContainerName(outer.getName().getString());
    }
    return sym;
  }

  public List<? extends Location> getDefinitions(final String docUri,
      final int line, final int character) {
    ArrayList<Location> result = new ArrayList<>();
    SomStructures probe = getProbe(docUri);
    if (probe == null) {
      return result;
    }

    // +1 to get to one based index
    ExpressionNode node = probe.getElementAt(line + 1, character);

    if (node == null) {
      return result;
    }

    if (ServerLauncher.DEBUG) {
      reportError(
          "Node at " + (line + 1) + ":" + character + " " + node.getClass().getSimpleName());
    }

    if (node instanceof Send) {
      SSymbol name = ((Send) node).getSelector();
      addAllDefinitions(result, name);
    } else if (node instanceof NonLocalVariableNode) {
      result.add(SomAdapter.getLocation(((NonLocalVariableNode) node).getLocal().source));
    } else if (node instanceof LocalVariableNode) {
      result.add(SomAdapter.getLocation(((LocalVariableNode) node).getLocal().source));
    } else if (node instanceof LocalArgumentReadNode) {
      result.add(SomAdapter.getLocation(((LocalArgumentReadNode) node).getArg().source));
    } else if (node instanceof NonLocalArgumentReadNode) {
      result.add(SomAdapter.getLocation(((NonLocalArgumentReadNode) node).getArg().source));
    } else {
      if (ServerLauncher.DEBUG) {
        reportError("GET DEFINITION, unsupported node: " + node.getClass().getSimpleName());
      }
    }
    return result;
  }

  private void addAllDefinitions(final ArrayList<Location> result, final SSymbol name) {
    synchronized (structuralProbes) {
      for (SomStructures s : structuralProbes.values()) {
        s.getDefinitionsFor(name, result);
      }
    }
  }

  public void reportError(final String msgStr) {
    MessageParams msg = new MessageParams();
    msg.setType(MessageType.Log);
    msg.setMessage(msgStr);

    client.logMessage(msg);

    ServerLauncher.logErr(msgStr);
  }

  public void reportDiagnostics(final List<Diagnostic> diagnostics,
      final String documentUri) {
    if (diagnostics != null) {
      PublishDiagnosticsParams result = new PublishDiagnosticsParams();
      result.setDiagnostics(diagnostics);
      result.setUri(documentUri);
      client.publishDiagnostics(result);
    }
  }

  public CompletionList getCompletions(final String docUri, final int line,
      final int character) {
    CompletionList result = new CompletionList();
    result.setIsIncomplete(true);

    SomStructures probe = getProbe(docUri);
    if (probe == null) {
      return result;
    }

    // TODO: this expects that this can be parsed without issues...
    // +1 to get to one based index, - 1 to get back into the element
    ExpressionNode node = probe.getElementAt(line + 1, Math.max(character - 1, 0));
    if (node == null) {
      return result;
    }

    SSymbol sym = null;
    if (node instanceof Send) {
      sym = ((Send) node).getSelector();
    } else {
      if (ServerLauncher.DEBUG) {
        reportError("GET COMPLETIONS, unsupported node: " + node.getClass().getSimpleName());
      }
    }

    if (sym != null) {
      Set<CompletionItem> completion = new HashSet<>();
      Collection<SomStructures> probes = getProbes();

      for (SomStructures s : probes) {
        s.getCompletions(sym, completion);
      }
      result.setItems(new ArrayList<>(completion));
    }

    return result;
  }

  private static final class SomCompiler extends SourcecodeCompiler {

    public SomCompiler(final SomLanguage language) {
      super(language);
      assert language != null;
    }

    @Override
    public MixinDefinition compileModule(final Source source,
        final StructuralProbe structuralProbe) throws ProgramDefinitionError {
      SomParser parser = new SomParser(source.getCharacters().toString(), source.getLength(),
          source, (SomStructures) structuralProbe, language);
      return compile(parser, source);
    }
  }

  public void getCodeLenses(final List<CodeLens> codeLenses,
      final String documentUri) {
    String path;
    try {
      path = docUriToNormalizedPath(documentUri);
    } catch (URISyntaxException e) {
      return;
    }

    SomStructures probe;
    synchronized (path) {
      probe = structuralProbes.get(path);
    }

    if (probe != null) {
      for (MixinDefinition c : probe.getClasses()) {
        SomMinitest.checkForTests(c, codeLenses, documentUri);
      }
    }
  }
}
