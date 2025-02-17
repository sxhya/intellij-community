// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.ex;

import com.intellij.analysis.AnalysisScope;
import com.intellij.analysis.AnalysisUIOptions;
import com.intellij.analysis.PerformAnalysisInBackgroundOption;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.ProblemHighlightFilter;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsPass;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.actions.CleanupInspectionUtil;
import com.intellij.codeInspection.lang.GlobalInspectionContextExtension;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.reference.RefVisitor;
import com.intellij.codeInspection.ui.*;
import com.intellij.codeInspection.ui.actions.ExportHTMLAction;
import com.intellij.concurrency.JobLauncher;
import com.intellij.concurrency.JobLauncherImpl;
import com.intellij.concurrency.SensitiveProgressWrapper;
import com.intellij.configurationStore.JbXmlOutputter;
import com.intellij.diagnostic.ThreadDumper;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.FileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.GuiUtils;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

public class GlobalInspectionContextImpl extends GlobalInspectionContextBase {
  private static final int MAX_OPEN_GLOBAL_INSPECTION_XML_RESULT_FILES = SystemProperties.getIntProperty("max.open.global.inspection.xml.files", 50);
  private static final boolean INSPECT_INJECTED_PSI = SystemProperties.getBooleanProperty("idea.batch.inspections.inspect.injected.psi", true);
  private static final Logger LOG = Logger.getInstance(GlobalInspectionContextImpl.class);
  @SuppressWarnings("StaticNonFinalField")
  @TestOnly
  public static volatile boolean TESTING_VIEW;
  public static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.toolWindowGroup("Inspection Results", ToolWindowId.INSPECTION);

  private final NotNullLazyValue<? extends ContentManager> myContentManager;
  private volatile InspectionResultsView myView;
  private volatile String myOutputPath;
  private Content myContent;
  private volatile boolean myViewClosed = true;
  private long myInspectionStartedTimestamp;
  private GlobalReportedProblemFilter myGlobalReportedProblemFilter;
  private ReportedProblemFilter myReportedProblemFilter;

  public GlobalInspectionContextImpl(@NotNull Project project, @NotNull NotNullLazyValue<? extends ContentManager> contentManager) {
    super(project);
    myContentManager = contentManager;
  }

  @NotNull
  private ContentManager getContentManager() {
    return myContentManager.getValue();
  }

  public ReportedProblemFilter getReportedProblemFilter() {
    return myReportedProblemFilter;
  }

  public void setReportedProblemFilter(ReportedProblemFilter reportedProblemFilter) {
    myReportedProblemFilter = reportedProblemFilter;
  }

  public GlobalReportedProblemFilter getGlobalReportedProblemFilter() {
    return myGlobalReportedProblemFilter;
  }

  public void setGlobalReportedProblemFilter(GlobalReportedProblemFilter reportedProblemFilter) {
    myGlobalReportedProblemFilter = reportedProblemFilter;
  }

  public void addView(@NotNull InspectionResultsView view,
                      @NotNull String title,
                      boolean isOffline) {
    LOG.assertTrue(myContent == null, "GlobalInspectionContext is busy under other view now");
    myView = view;
    if (!isOffline) {
      myView.setUpdating(true);
    }
    myContent = ContentFactory.SERVICE.getInstance().createContent(view, title, false);
    myContent.setHelpId(InspectionResultsView.HELP_ID);
    myContent.setDisposer(myView);
    Disposer.register(myContent, () -> {
      if (myView != null) {
        close(false);
      }
      myContent = null;
    });

    ContentManager contentManager = getContentManager();
    contentManager.addContent(myContent);
    contentManager.setSelectedContent(myContent);

    ToolWindowManager.getInstance(getProject()).getToolWindow(ToolWindowId.INSPECTION).activate(null);
  }

  public void addView(@NotNull InspectionResultsView view) {
    addView(view, InspectionsBundle.message(view.isSingleInspectionRun() ?
                                            "inspection.results.for.inspection.toolwindow.title" :
                                            "inspection.results.for.profile.toolwindow.title",
                                            view.getCurrentProfileName(), getCurrentScope().getShortenName()), false);

  }

  @Override
  public void doInspections(@NotNull final AnalysisScope scope) {
    if (myContent != null) {
      getContentManager().removeContent(myContent, true);
    }
    super.doInspections(scope);
  }

  public void launchInspectionsOffline(@NotNull final AnalysisScope scope,
                                       @NotNull final String outputPath,
                                       final boolean runGlobalToolsOnly,
                                       @NotNull final List<? super Path> inspectionsResults) {
    performInspectionsWithProgressAndExportResults(scope, runGlobalToolsOnly, true, outputPath, inspectionsResults);
  }

  public void performInspectionsWithProgressAndExportResults(@NotNull final AnalysisScope scope,
                                                             final boolean runGlobalToolsOnly,
                                                             final boolean isOfflineInspections,
                                                             @NotNull final String outputPath,
                                                             @NotNull final List<? super Path> inspectionsResults) {
    cleanupTools();
    setCurrentScope(scope);

    final Runnable action = () -> {
      myOutputPath = outputPath;
      try {
        performInspectionsWithProgress(scope, runGlobalToolsOnly, isOfflineInspections);
        exportResultsSmart(inspectionsResults, outputPath);
      }
      finally {
        myOutputPath = null;
      }
    };
    if (isOfflineInspections) {
      ApplicationManager.getApplication().runReadAction(action);
    }
    else {
      action.run();
    }
  }

  protected void exportResults(@NotNull List<? super Path> inspectionsResults,
                               @NotNull List<? extends Tools> inspections,
                               @NotNull String outputPath,
                               @Nullable XMLOutputFactory xmlOutputFactory) {
    if (xmlOutputFactory == null) {
      xmlOutputFactory = XMLOutputFactory.newInstance();
    }

    BufferedWriter[] writers = new BufferedWriter[inspections.size()];
    XMLStreamWriter[] xmlWriters = new XMLStreamWriter[inspections.size()];

    try {
      int i = 0;
      for (Tools inspection : inspections) {
        inspectionsResults.add(ExportHTMLAction.getInspectionResultPath(outputPath, inspection.getShortName()));
        try {
          BufferedWriter writer = ExportHTMLAction.getWriter(outputPath, inspection.getShortName());
          writers[i] = writer;
          XMLStreamWriter xmlWriter = xmlOutputFactory.createXMLStreamWriter(writer);
          xmlWriters[i++] = xmlWriter;
          xmlWriter.writeStartElement(GlobalInspectionContextBase.PROBLEMS_TAG_NAME);
          xmlWriter.writeCharacters("\n");
          xmlWriter.flush();
        }
        catch (FileNotFoundException | XMLStreamException e) {
          LOG.error(e);
        }
      }

      getRefManager().iterate(new RefVisitor() {
        @Override
        public void visitElement(@NotNull final RefEntity refEntity) {
          int i = 0;
          for (Tools tools : inspections) {
            for (ScopeToolState state : tools.getTools()) {
              try {
                InspectionToolWrapper toolWrapper = state.getTool();
                InspectionToolPresentation presentation = getPresentation(toolWrapper);
                BufferedWriter writer = writers[i];
                if (writer != null &&
                    (myGlobalReportedProblemFilter == null ||
                     myGlobalReportedProblemFilter.shouldReportProblem(refEntity, toolWrapper.getShortName())) ) {
                  presentation.exportResults(e -> {
                    try {
                      JbXmlOutputter.collapseMacrosAndWrite(e, getProject(), writer);
                      writer.flush();
                    }
                    catch (IOException e1) {
                      throw new RuntimeException(e1);
                    }
                  }, refEntity, d -> false);
                }
              }
              catch (Throwable e) {
                LOG.error("Problem when exporting: " + refEntity.getExternalName(), e);
              }
            }
            i++;
          }
        }
      });

      for (XMLStreamWriter xmlWriter : xmlWriters) {
        if (xmlWriter != null) {
          try {
            xmlWriter.writeEndElement();
            xmlWriter.flush();
          }
          catch (XMLStreamException e) {
            LOG.error(e);
          }
        }
      }
    }
    finally {
      for (BufferedWriter writer : writers) {
        if (writer != null) {
          try {
            writer.close();
          }
          catch (IOException e) {
            LOG.error(e);
          }
        }
      }
    }
  }

  private void exportResultsSmart(@NotNull List<? super Path> inspectionsResults, @NotNull String outputPath) {
    final List<Tools> globalToolsWithProblems = new ArrayList<>();
    for (Map.Entry<String,Tools> entry : getTools().entrySet()) {
      final Tools sameTools = entry.getValue();
      boolean hasProblems = false;
      String toolName = entry.getKey();
      if (sameTools != null) {
        for (ScopeToolState toolDescr : sameTools.getTools()) {
          InspectionToolWrapper toolWrapper = toolDescr.getTool();
          if (toolWrapper instanceof LocalInspectionToolWrapper) {
            hasProblems = ExportHTMLAction.getInspectionResultFile(outputPath, toolWrapper.getShortName()).exists();
          }
          else {
            InspectionToolPresentation presentation = getPresentation(toolWrapper);
            presentation.updateContent();
            if (presentation.hasReportedProblems()) {
              globalToolsWithProblems.add(sameTools);
              LOG.assertTrue(!hasProblems, toolName);
              break;
            }
          }
        }
      }

      // close "problem" tag for local inspections (see DefaultInspectionToolPresentation.addProblemElement())
      if (hasProblems) {
        try {
          final Path file = ExportHTMLAction.getInspectionResultPath(outputPath, sameTools.getShortName());
          inspectionsResults.add(file);
          Files.write(file, ("</" + PROBLEMS_TAG_NAME + ">").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
    }

    // export global inspections
    if (!globalToolsWithProblems.isEmpty()) {
      XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();
      StreamEx.ofSubLists(globalToolsWithProblems, MAX_OPEN_GLOBAL_INSPECTION_XML_RESULT_FILES).forEach(inspections ->
        exportResults(inspectionsResults, inspections, outputPath, xmlOutputFactory));
    }
  }

  public void resolveElement(@NotNull InspectionProfileEntry tool, @NotNull PsiElement element) {
    final RefElement refElement = getRefManager().getReference(element);
    if (refElement == null) return;
    final Tools tools = getTools().get(tool.getShortName());
    if (tools != null){
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        InspectionToolPresentation presentation = getPresentationOrNull(toolWrapper);
        if (presentation != null) {
          resolveElementRecursively(presentation, refElement);
        }
      }
    }
  }

  public InspectionResultsView getView() {
    return myView;
  }

  public String getOutputPath() {
    return myOutputPath;
  }

  private static void resolveElementRecursively(@NotNull InspectionToolPresentation presentation, @NotNull RefEntity refElement) {
    presentation.suppressProblem(refElement);
    final List<RefEntity> children = refElement.getChildren();
    for (RefEntity child : children) {
      resolveElementRecursively(presentation, child);
    }
  }

  @NotNull
  public AnalysisUIOptions getUIOptions() {
    return AnalysisUIOptions.getInstance(getProject());
  }

  public void setSplitterProportion(final float proportion) {
    getUIOptions().SPLITTER_PROPORTION = proportion;
  }

  @NotNull
  public ToggleAction createToggleAutoscrollAction() {
    return getUIOptions().getAutoScrollToSourceHandler().createToggleAction();
  }

  @Override
  protected void launchInspections(@NotNull final AnalysisScope scope) {
    myViewClosed = false;
    super.launchInspections(scope);
  }

  @NotNull
  @Override
  protected PerformInBackgroundOption createOption() {
    return new PerformAnalysisInBackgroundOption(getProject());
  }

  @Override
  protected void notifyInspectionsFinished(@NotNull final AnalysisScope scope) {
    //noinspection TestOnlyProblems
    if (ApplicationManager.getApplication().isUnitTestMode() && !TESTING_VIEW) return;
    LOG.assertTrue(ApplicationManager.getApplication().isDispatchThread());
    long elapsed = System.currentTimeMillis() - myInspectionStartedTimestamp;
    LOG.info("Code inspection finished. Took " + elapsed + "ms");
    if (getProject().isDisposed()) return;

    InspectionResultsView newView = myView == null ? new InspectionResultsView(this, new InspectionRVContentProviderImpl()) : null;
    if (!(myView == null ? newView : myView).hasProblems()) {
      int totalFiles = getStdJobDescriptors().BUILD_GRAPH.getTotalAmount(); // do not use invalidated scope
      NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message",
                                                                      totalFiles,
                                                                      scope.getShortenName()),
                                            MessageType.INFO).notify(getProject());
      close(true);
      if (newView != null) {
        Disposer.dispose(newView);
      }
    }
    else if (newView != null && !newView.isDisposed() && getCurrentScope() != null) {
      addView(newView);
      newView.update();
    }
    if (myView != null) {
      myView.setUpdating(false);
    }
  }

  @Override
  protected void runTools(@NotNull final AnalysisScope scope, boolean runGlobalToolsOnly, boolean isOfflineInspections) {
    myInspectionStartedTimestamp = System.currentTimeMillis();
    final ProgressIndicator progressIndicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
    if (progressIndicator == null) {
      throw new IncorrectOperationException("Must be run under progress");
    }
    if (!isOfflineInspections && ApplicationManager.getApplication().isDispatchThread()) {
      throw new IncorrectOperationException("Must not start inspections from within EDT");
    }
    if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
      throw new IncorrectOperationException("Must not start inspections from within write action");
    }
    // in offline inspection application we don't care about global read action
    if (!isOfflineInspections && ApplicationManager.getApplication().isReadAccessAllowed()) {
      throw new IncorrectOperationException("Must not start inspections from within global read action");
    }
    final InspectionManager inspectionManager = InspectionManager.getInstance(getProject());
    ((RefManagerImpl)getRefManager()).initializeAnnotators();
    final List<Tools> globalTools = new ArrayList<>();
    final List<Tools> localTools = new ArrayList<>();
    final List<Tools> globalSimpleTools = new ArrayList<>();
    initializeTools(globalTools, localTools, globalSimpleTools);
    appendPairedInspectionsForUnfairTools(globalTools, globalSimpleTools, localTools);
    runGlobalTools(scope, inspectionManager, globalTools, isOfflineInspections);

    if (runGlobalToolsOnly || localTools.isEmpty() && globalSimpleTools.isEmpty()) return;

    SearchScope searchScope = ReadAction.compute(scope::toSearchScope);
    final Set<VirtualFile> localScopeFiles = searchScope instanceof LocalSearchScope ? new THashSet<>() : null;
    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      tool.inspectionStarted(inspectionManager, this, getPresentation(toolWrapper));
    }

    final boolean headlessEnvironment = ApplicationManager.getApplication().isHeadlessEnvironment();
    final Map<String, InspectionToolWrapper> map = getInspectionWrappersMap(localTools);

    final BlockingQueue<PsiFile> filesToInspect = new ArrayBlockingQueue<>(1000);
    // use original progress indicator here since we don't want it to cancel on write action start
    ProgressIndicator iteratingIndicator = new SensitiveProgressWrapper(progressIndicator);
    Future<?> future = startIterateScopeInBackground(scope, localScopeFiles, headlessEnvironment, filesToInspect, iteratingIndicator);

    Processor<PsiFile> processor = file -> {
      ProgressManager.checkCanceled();
      Boolean readActionSuccess = DumbService.getInstance(getProject()).tryRunReadActionInSmartMode(() -> {
        if (!file.isValid()) {
          return true;
        }
        VirtualFile virtualFile = file.getVirtualFile();
        if (!scope.contains(virtualFile)) {
          LOG.info(file.getName() + "; scope: " + scope + "; " + virtualFile);
          return true;
        }
        boolean includeDoNotShow = includeDoNotShow(getCurrentProfile());
        inspectFile(file, getEffectiveRange(searchScope, file), inspectionManager, map,
                    getWrappersFromTools(globalSimpleTools, file, includeDoNotShow, wrapper -> !(wrapper.getTool() instanceof ExternalAnnotatorBatchInspection)),
                    getWrappersFromTools(localTools, file, includeDoNotShow, wrapper -> !(wrapper.getTool() instanceof ExternalAnnotatorBatchInspection)));
        return true;
      }, "Inspect code is not available until indices are ready");
      if (readActionSuccess == null || !readActionSuccess) {
        throw new ProcessCanceledException();
      }

      boolean includeDoNotShow = includeDoNotShow(getCurrentProfile());
      List<InspectionToolWrapper> externalAnnotatable = ContainerUtil.concat(
        getWrappersFromTools(localTools, file, includeDoNotShow, wrapper -> wrapper.getTool() instanceof ExternalAnnotatorBatchInspection),
        getWrappersFromTools(globalSimpleTools, file, includeDoNotShow, wrapper -> wrapper.getTool() instanceof ExternalAnnotatorBatchInspection));
      externalAnnotatable.forEach(wrapper -> {
          ProblemDescriptor[] descriptors = ((ExternalAnnotatorBatchInspection)wrapper.getTool()).checkFile(file, this, inspectionManager);
          InspectionToolPresentation toolPresentation = getPresentation(wrapper);
          ReadAction.run(() -> BatchModeDescriptorsUtil
            .addProblemDescriptors(Arrays.asList(descriptors), false, this, null, CONVERT, toolPresentation));
        });

      return true;
    };
    try {
      final Queue<PsiFile> filesFailedToInspect = new LinkedBlockingQueue<>();
      while (true) {
        Disposable disposable = Disposer.newDisposable();
        ProgressIndicator wrapper = new DaemonProgressIndicator();
        dependentIndicators.add(wrapper);
        try {
          // avoid "attach listener"/"write action" race
          ReadAction.run(() -> {
            wrapper.start();
            ProgressIndicatorUtils.forceWriteActionPriority(wrapper, disposable);
            // there is a chance we are racing with write action, in which case just registered listener might not be called, retry.
            if (ApplicationManagerEx.getApplicationEx().isWriteActionPending()) {
              throw new ProcessCanceledException();
            }
          });
          // use wrapper here to cancel early when write action start but do not affect the original indicator
          ((JobLauncherImpl)JobLauncher.getInstance()).processQueue(filesToInspect, filesFailedToInspect, wrapper, TOMBSTONE, processor);
          break;
        }
        catch (ProcessCanceledException e) {
          progressIndicator.checkCanceled();
          // PCE may be thrown from inside wrapper when write action started
          // go on with the write and then resume processing the rest of the queue
          assert isOfflineInspections || !ApplicationManager.getApplication().isReadAccessAllowed()
            : "Must be outside read action. PCE=\n" + ExceptionUtil.getThrowableText(e);
          assert isOfflineInspections || !ApplicationManager.getApplication().isDispatchThread()
            : "Must be outside EDT. PCE=\n" + ExceptionUtil.getThrowableText(e);

          // wait for write action to complete
          ApplicationManager.getApplication().runReadAction(EmptyRunnable.getInstance());
        }
        finally {
          dependentIndicators.remove(wrapper);
          Disposer.dispose(disposable);
        }
      }
    }
    finally {
      iteratingIndicator.cancel(); // tell file scanning thread to stop
      filesToInspect.clear(); // let file scanning thread a chance to put TOMBSTONE and complete
      try {
        future.get(30, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        LOG.error("Thread dump: \n"+ThreadDumper.dumpThreadsToString(), e);
      }
    }

    ProgressManager.checkCanceled();

    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, map);
      tool.inspectionFinished(inspectionManager, this, problemDescriptionProcessor);

    }

    addProblemsToView(globalSimpleTools);
  }

  private static TextRange getEffectiveRange(SearchScope searchScope, PsiFile file) {
    if (searchScope instanceof LocalSearchScope) {
      List<PsiElement> scopeFileElements = ContainerUtil.filter(((LocalSearchScope)searchScope).getScope(), e -> e.getContainingFile() == file);
      if (!scopeFileElements.isEmpty()) {
        int start = -1;
        int end = -1;
        for (PsiElement scopeElement : scopeFileElements) {
          TextRange elementRange = scopeElement.getTextRange();
          start = start == -1 ? elementRange.getStartOffset() : Math.min(elementRange.getStartOffset(), start);
          end = end == -1 ? elementRange.getEndOffset() : Math.max(elementRange.getEndOffset(), end);
        }
        return new TextRange(start, end);
      }
    }
    return new TextRange(0, file.getTextLength());
  }

  // indicators which should be canceled once the main indicator myProgressIndicator is
  private final List<ProgressIndicator> dependentIndicators = ContainerUtil.createLockFreeCopyOnWriteList();

  @Override
  protected void canceled() {
    super.canceled();
    dependentIndicators.forEach(ProgressIndicator::cancel);
  }

  private void inspectFile(@NotNull final PsiFile file,
                           @NotNull final TextRange range,
                           @NotNull final InspectionManager inspectionManager,
                           @NotNull final Map<String, InspectionToolWrapper> wrappersMap,
                           @NotNull List<? extends GlobalInspectionToolWrapper> globalSimpleTools,
                           @NotNull List<? extends LocalInspectionToolWrapper> localTools) {
    Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
    if (document == null) return;

    LocalInspectionsPass pass = new LocalInspectionsPass(file, document, range.getStartOffset(),
                                                         range.getEndOffset(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                         HighlightInfoProcessor.getEmpty(), INSPECT_INJECTED_PSI);
    try {
      pass.doInspectInBatch(this, inspectionManager, localTools);

      assertUnderDaemonProgress();

      JobLauncher.getInstance().invokeConcurrentlyUnderProgress(globalSimpleTools, ProgressManager.getGlobalProgressIndicator(), toolWrapper -> {
        GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
        ProblemsHolder holder = new ProblemsHolder(inspectionManager, file, false);
        ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, wrappersMap);
        tool.checkFile(file, inspectionManager, holder, this, problemDescriptionProcessor);
        InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
        BatchModeDescriptorsUtil.addProblemDescriptors(holder.getResults(), false, this, null, CONVERT, toolPresentation);
        return true;
      });
      VirtualFile virtualFile = file.getVirtualFile();
      String displayUrl = ProjectUtilCore.displayUrlRelativeToProject(virtualFile, virtualFile.getPresentableUrl(), getProject(), true, false);
      incrementJobDoneAmount(getStdJobDescriptors().LOCAL_ANALYSIS, displayUrl);
    }
    catch (ProcessCanceledException | IndexNotReadyException e) {
      throw e;
    }
    catch (Throwable e) {
      LOG.error("In file: " + file.getName(), e);
    }
    finally {
      InjectedLanguageManager.getInstance(getProject()).dropFileCaches(file);
    }
  }

  protected boolean includeDoNotShow(final InspectionProfile profile) {
    return profile.getSingleTool() != null;
  }

  private static final PsiFile TOMBSTONE = PsiUtilCore.NULL_PSI_FILE;

  @NotNull
  private Future<?> startIterateScopeInBackground(@NotNull final AnalysisScope scope,
                                                  @Nullable final Collection<? super VirtualFile> localScopeFiles,
                                                  final boolean headlessEnvironment,
                                                  @NotNull final BlockingQueue<? super PsiFile> outFilesToInspect,
                                                  @NotNull final ProgressIndicator progressIndicator) {
    Task.Backgroundable task = new Task.Backgroundable(getProject(), "Scanning Files to Inspect") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          final FileIndex fileIndex = ProjectRootManager.getInstance(getProject()).getFileIndex();
          scope.accept(file -> {
            ProgressManager.checkCanceled();
            if (ProjectUtil.isProjectOrWorkspaceFile(file) || !fileIndex.isInContent(file)) return true;

            PsiFile psiFile = ReadAction.compute(() -> {
              if (getProject().isDisposed()) throw new ProcessCanceledException();
              PsiFile psi = PsiManager.getInstance(getProject()).findFile(file);
              Document document = psi == null ? null : shouldProcess(psi, headlessEnvironment, localScopeFiles);
              if (document != null) {
                return psi;
              }
              return null;
            });
            // do not inspect binary files
            if (psiFile != null) {
              try {
                if (ApplicationManager.getApplication().isReadAccessAllowed()) {
                  throw new IllegalStateException("Must not have read action");
                }
                outFilesToInspect.put(psiFile);
              }
              catch (InterruptedException e) {
                LOG.error(e);
              }
            }
            ProgressManager.checkCanceled();
            return true;
          });
        }
        catch (ProcessCanceledException e) {
          // ignore, but put tombstone
        }
        finally {
          try {
            outFilesToInspect.put(TOMBSTONE);
          }
          catch (InterruptedException e) {
            LOG.error(e);
          }
        }
      }
    };
    return ((CoreProgressManager)ProgressManager.getInstance()).runProcessWithProgressAsynchronously(task, progressIndicator, null);
  }

  private Document shouldProcess(@NotNull PsiFile file, boolean headlessEnvironment, @Nullable Collection<? super VirtualFile> localScopeFiles) {
    final VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    if (isBinary(file)) return null; //do not inspect binary files

    if (myViewClosed && !headlessEnvironment) {
      throw new ProcessCanceledException();
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Running local inspections on " + virtualFile.getPath());
    }

    if (SingleRootFileViewProvider.isTooLargeForIntelligence(virtualFile)) return null;
    if (localScopeFiles != null && !localScopeFiles.add(virtualFile)) return null;
    if (!ProblemHighlightFilter.shouldProcessFileInBatch(file)) return null;

    return PsiDocumentManager.getInstance(getProject()).getDocument(file);
  }

  private void runGlobalTools(@NotNull final AnalysisScope scope,
                              @NotNull final InspectionManager inspectionManager,
                              @NotNull List<? extends Tools> globalTools,
                              boolean isOfflineInspections) {
    LOG.assertTrue(!ApplicationManager.getApplication().isReadAccessAllowed() || isOfflineInspections, "Must not run under read action, too unresponsive");
    final List<InspectionToolWrapper> needRepeatSearchRequest = new ArrayList<>();

    SearchScope initialSearchScope = ReadAction.compute(() -> scope.toSearchScope());
    final boolean canBeExternalUsages = !(scope.getScopeType() == AnalysisScope.PROJECT && scope.isIncludeTestSource());
    for (Tools tools : globalTools) {
      for (ScopeToolState state : tools.getTools()) {
        if (!state.isEnabled()) continue;
        NamedScope stateScope = state.getScope(getProject());
        if (stateScope == null) continue;

        AnalysisScope scopeForState = new AnalysisScope(GlobalSearchScopesCore.filterScope(getProject(), stateScope)
                                                                              .intersectWith(initialSearchScope), getProject());
        final InspectionToolWrapper toolWrapper = state.getTool();
        final GlobalInspectionTool tool = (GlobalInspectionTool)toolWrapper.getTool();
        final InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
        try {
          if (tool.isGraphNeeded()) {
            try {
              ((RefManagerImpl)getRefManager()).findAllDeclarations();
            }
            catch (Throwable e) {
              getStdJobDescriptors().BUILD_GRAPH.setDoneAmount(0);
              throw e;
            }
          }
          ApplicationManager.getApplication().runReadAction(() -> {
            tool.runInspection(scopeForState, inspectionManager, this, toolPresentation);
            //skip phase when we are sure that scope already contains everything, unused declaration though needs to proceed with its suspicious code
            if ((canBeExternalUsages || tool.getAdditionalJobs(this) != null) &&
                tool.queryExternalUsagesRequests(inspectionManager, this, toolPresentation)) {
              needRepeatSearchRequest.add(toolWrapper);
            }
          });
        }
        catch (ProcessCanceledException | IndexNotReadyException e) {
          throw e;
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }

    for (GlobalInspectionContextExtension extension : myExtensions.values()) {
      try {
        extension.performPostRunActivities(needRepeatSearchRequest, this);
      }
      catch (ProcessCanceledException | IndexNotReadyException e) {
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }

    addProblemsToView(globalTools);
  }

  public ActionCallback initializeViewIfNeeded() {
    if (myView != null) {
      return ActionCallback.DONE;
    }
    return ApplicationManager.getApplication().getInvokator().invokeLater(() -> {
      if (getCurrentScope() == null) return;
      InspectionResultsView view = getView();
      if (view == null) {
        view = new InspectionResultsView(this, new InspectionRVContentProviderImpl());
        addView(view);
      }
    }, x -> getCurrentScope() == null);
  }

  private void appendPairedInspectionsForUnfairTools(@NotNull List<? super Tools> globalTools,
                                                     @NotNull List<? super Tools> globalSimpleTools,
                                                     @NotNull List<Tools> localTools) {
    Tools[] larray = localTools.toArray(new Tools[0]);
    for (Tools tool : larray) {
      LocalInspectionToolWrapper toolWrapper = (LocalInspectionToolWrapper)tool.getTool();
      LocalInspectionTool localTool = toolWrapper.getTool();
      if (localTool instanceof PairedUnfairLocalInspectionTool) {
        String batchShortName = ((PairedUnfairLocalInspectionTool)localTool).getInspectionForBatchShortName();
        InspectionProfile currentProfile = getCurrentProfile();
        InspectionToolWrapper batchInspection;
        final InspectionToolWrapper pairedWrapper = currentProfile.getInspectionTool(batchShortName, getProject());
        batchInspection = pairedWrapper != null ? pairedWrapper.createCopy() : null;
        if (batchInspection != null && !getTools().containsKey(batchShortName)) {
          // add to existing inspections to run
          InspectionProfileEntry batchTool = batchInspection.getTool();
          final ScopeToolState defaultState = tool.getDefaultState();
          ToolsImpl newTool = new ToolsImpl(batchInspection, defaultState.getLevel(), true, defaultState.isEnabled());
          for (ScopeToolState state : tool.getTools()) {
            final NamedScope scope = state.getScope(getProject());
            if (scope != null) {
              newTool.addTool(scope, batchInspection, state.isEnabled(), state.getLevel());
            }
          }
          if (batchTool instanceof LocalInspectionTool) localTools.add(newTool);
          else if (batchTool instanceof GlobalSimpleInspectionTool) globalSimpleTools.add(newTool);
          else if (batchTool instanceof GlobalInspectionTool) globalTools.add(newTool);
          else throw new AssertionError(batchTool);
          myTools.put(batchShortName, newTool);
          batchInspection.initialize(this);
        }
      }
    }
  }

  @NotNull
  private static <T extends InspectionToolWrapper> List<T> getWrappersFromTools(@NotNull List<? extends Tools> localTools,
                                                                                @NotNull PsiFile file,
                                                                                boolean includeDoNotShow,
                                                                                @NotNull Predicate<? super T> filter) {
    return ContainerUtil.mapNotNull(localTools, tool -> {
      //noinspection unchecked
      T unwrapped = (T)tool.getEnabledTool(file, includeDoNotShow);
      if (unwrapped == null) return null;
      return filter.test(unwrapped) ? unwrapped : null;
    });
  }

  @NotNull
  private ProblemDescriptionsProcessor getProblemDescriptionProcessor(@NotNull final GlobalInspectionToolWrapper toolWrapper,
                                                                      @NotNull final Map<String, InspectionToolWrapper> wrappersMap) {
    return new ProblemDescriptionsProcessor() {
      @Override
      public void addProblemElement(@Nullable RefEntity refEntity, @NotNull CommonProblemDescriptor... commonProblemDescriptors) {
        for (CommonProblemDescriptor problemDescriptor : commonProblemDescriptors) {
          if (!(problemDescriptor instanceof ProblemDescriptor)) {
            continue;
          }
          if (SuppressionUtil.inspectionResultSuppressed(((ProblemDescriptor)problemDescriptor).getPsiElement(), toolWrapper.getTool())) {
            continue;
          }
          ProblemGroup problemGroup = ((ProblemDescriptor)problemDescriptor).getProblemGroup();

          InspectionToolWrapper targetWrapper = problemGroup == null ? toolWrapper : wrappersMap.get(problemGroup.getProblemName());
          if (targetWrapper != null) { // Else it's switched off
            InspectionToolPresentation toolPresentation = getPresentation(targetWrapper);
            toolPresentation.addProblemElement(refEntity, problemDescriptor);
          }
        }
      }
    };
  }

  @NotNull
  private static Map<String, InspectionToolWrapper> getInspectionWrappersMap(@NotNull List<? extends Tools> tools) {
    Map<String, InspectionToolWrapper> name2Inspection = new HashMap<>(tools.size());
    for (Tools tool : tools) {
      InspectionToolWrapper toolWrapper = tool.getTool();
      name2Inspection.put(toolWrapper.getShortName(), toolWrapper);
    }

    return name2Inspection;
  }

  private static final TripleFunction<LocalInspectionTool,PsiElement,GlobalInspectionContext,RefElement> CONVERT =
    (tool, elt, context) -> {
      PsiNamedElement problemElement = PsiTreeUtil.getNonStrictParentOfType(elt, PsiFile.class);

      RefElement refElement = context.getRefManager().getReference(problemElement);
      if (refElement == null && problemElement != null) {  // no need to lose collected results
        refElement = GlobalInspectionContextUtil.retrieveRefElement(elt, context);
      }
      return refElement;
    };


  @Override
  public void close(boolean noSuspiciousCodeFound) {
    if (!noSuspiciousCodeFound) {
      if (myView.isRerun()) {
        myViewClosed = true;
        myView = null;
      }
      if (myView == null) {
        return;
      }
    }
    if (myContent != null) {
      final ContentManager contentManager = getContentManager();
      contentManager.removeContent(myContent, true);
    }
    myViewClosed = true;
    myView = null;
    ((InspectionManagerEx)InspectionManager.getInstance(getProject())).closeRunningContext(this);
    myPresentationMap.clear();
    super.close(noSuspiciousCodeFound);
  }

  @Override
  public void cleanup() {
    if (myView != null) {
      myView.setUpdating(false);
    }
    else {
      myPresentationMap.clear();
      super.cleanup();
    }
  }

  public void refreshViews() {
    if (myView != null) {
      myView.getTree().getInspectionTreeModel().reload();
    }
  }

  private final ConcurrentMap<InspectionToolWrapper, InspectionToolPresentation> myPresentationMap = ContainerUtil.newConcurrentMap();

  @Nullable
  private InspectionToolPresentation getPresentationOrNull(@NotNull InspectionToolWrapper toolWrapper) {
    return myPresentationMap.get(toolWrapper);
  }

  @NotNull
  public InspectionToolPresentation getPresentation(@NotNull InspectionToolWrapper toolWrapper) {
    InspectionToolPresentation presentation = myPresentationMap.get(toolWrapper);
    if (presentation == null) {
      String presentationClass = StringUtil.notNullize(toolWrapper.myEP == null ? null : toolWrapper.myEP.presentation, DefaultInspectionToolPresentation.class.getName());

      try {
        InspectionEP extension = toolWrapper.getExtension();
        ClassLoader classLoader = extension == null ? getClass().getClassLoader() : extension.getLoaderForClass();
        Constructor<?> constructor = Class.forName(presentationClass, true, classLoader)
                                          .getConstructor(InspectionToolWrapper.class, GlobalInspectionContextImpl.class);
        presentation = (InspectionToolPresentation)constructor.newInstance(toolWrapper, this);
      }
      catch (Exception e) {
        LOG.error(e);
        throw new RuntimeException(e);
      }
      presentation = ConcurrencyUtil.cacheOrGet(myPresentationMap, toolWrapper, presentation);
    }
    return presentation;
  }

  @Override
  public void codeCleanup(@NotNull final AnalysisScope scope,
                          @NotNull final InspectionProfile profile,
                          @Nullable final String commandName,
                          @Nullable final Runnable postRunnable,
                          final boolean modal,
                          @NotNull Predicate<? super ProblemDescriptor> shouldApplyFix) {
    String title = "Inspect Code...";
    Task task = modal ? new Task.Modal(getProject(), title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        cleanup(scope, profile, postRunnable, commandName, shouldApplyFix, indicator);
      }
    } : new Task.Backgroundable(getProject(), title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        cleanup(scope, profile, postRunnable, commandName, shouldApplyFix, indicator);
      }
    };
    ProgressManager.getInstance().run(task);
  }

  private void cleanup(@NotNull final AnalysisScope scope,
                       @NotNull InspectionProfile profile,
                       @Nullable final Runnable postRunnable,
                       @Nullable final String commandName,
                       @NotNull Predicate<? super ProblemDescriptor> shouldApplyFix,
                       @NotNull ProgressIndicator progressIndicator) {
    setCurrentScope(scope);
    final int fileCount = scope.getFileCount();
    progressIndicator.setIndeterminate(false);
    final SearchScope searchScope = ReadAction.compute(scope::toSearchScope);
    final TextRange range;
    if (searchScope instanceof LocalSearchScope) {
      final PsiElement[] elements = ((LocalSearchScope)searchScope).getScope();
      range = elements.length == 1 ? ReadAction.compute(elements[0]::getTextRange) : null;
    }
    else {
      range = null;
    }
    final Iterable<Tools> inspectionTools = ContainerUtil.filter(profile.getAllEnabledInspectionTools(getProject()), tools -> {
      assert tools != null;
      return tools.getTool().isCleanupTool();
    });
    boolean includeDoNotShow = includeDoNotShow(profile);
    final RefManagerImpl refManager = (RefManagerImpl)getRefManager();
    refManager.inspectionReadActionStarted();
    List<ProblemDescriptor> descriptors = new ArrayList<>();
    Set<PsiFile> files = new HashSet<>();
    try {
      scope.accept(new PsiElementVisitor() {
        private int myCount;
        @Override
        public void visitFile(PsiFile file) {
          progressIndicator.setFraction((double)++myCount / fileCount);
          if (isBinary(file)) return;
          final List<LocalInspectionToolWrapper> lTools = new ArrayList<>();
          for (final Tools tools : inspectionTools) {
            InspectionToolWrapper tool = tools.getEnabledTool(file, includeDoNotShow);
            if (tool instanceof GlobalInspectionToolWrapper) {
              tool = ((GlobalInspectionToolWrapper)tool).getSharedLocalInspectionToolWrapper();
            }
            if (tool != null) {
              lTools.add((LocalInspectionToolWrapper)tool);
              tool.initialize(GlobalInspectionContextImpl.this);
            }
          }

          if (!lTools.isEmpty()) {
            try {
              final LocalInspectionsPass pass = new LocalInspectionsPass(file, PsiDocumentManager.getInstance(getProject()).getDocument(file), range != null ? range.getStartOffset() : 0,
                                                                         range != null ? range.getEndOffset() : file.getTextLength(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                                         HighlightInfoProcessor.getEmpty(), true);
              Runnable runnable = () -> pass.doInspectInBatch(GlobalInspectionContextImpl.this, InspectionManager.getInstance(getProject()), lTools);
              ApplicationManager.getApplication().runReadAction(runnable);

              final Set<ProblemDescriptor> localDescriptors = new TreeSet<>(CommonProblemDescriptor.DESCRIPTOR_COMPARATOR);
              for (LocalInspectionToolWrapper tool : lTools) {
                InspectionToolPresentation toolPresentation = getPresentation(tool);
                for (CommonProblemDescriptor descriptor : toolPresentation.getProblemDescriptors()) {
                  if (descriptor instanceof ProblemDescriptor) {
                    localDescriptors.add((ProblemDescriptor)descriptor);
                  }
                }
              }

              if (searchScope instanceof LocalSearchScope) {
                for (Iterator<ProblemDescriptor> iterator = localDescriptors.iterator(); iterator.hasNext(); ) {
                  final ProblemDescriptor descriptor = iterator.next();
                  final TextRange infoRange = descriptor instanceof ProblemDescriptorBase ? ((ProblemDescriptorBase)descriptor).getTextRange() : null;
                  if (infoRange != null && !((LocalSearchScope)searchScope).containsRange(file, infoRange)) {
                    iterator.remove();
                  }
                }
              }
              if (!localDescriptors.isEmpty()) {
                for (ProblemDescriptor descriptor : localDescriptors) {
                  if (shouldApplyFix.test(descriptor)) {
                    descriptors.add(descriptor);
                  }
                }
                files.add(file);
              }
            }
            finally {
              myPresentationMap.clear();
            }
          }
        }
      });
    }
    finally {
      refManager.inspectionReadActionFinished();
    }

    if (files.isEmpty()) {
      GuiUtils.invokeLaterIfNeeded(() -> {
        if (commandName != null) {
          NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message", scope.getFileCount(), scope.getDisplayName()), MessageType.INFO).notify(getProject());
        }
        if (postRunnable != null) {
          postRunnable.run();
        }
      }, ModalityState.defaultModalityState());
      return;
    }

    Runnable runnable = () -> {
      if (!FileModificationService.getInstance().preparePsiElementsForWrite(files)) return;
      CleanupInspectionUtil.getInstance().applyFixesNoSort(getProject(), "Code Cleanup", descriptors, null, false, searchScope instanceof GlobalSearchScope);
      if (postRunnable != null) {
        postRunnable.run();
      }
    };
    TransactionGuard.submitTransaction(getProject(), runnable);
  }

  private static boolean isBinary(@NotNull PsiFile file) {
    return file instanceof PsiBinaryFile || file.getFileType().isBinary();
  }

  public boolean isViewClosed() {
    return myViewClosed;
  }

  private void addProblemsToView(List<? extends Tools> tools) {
    //noinspection TestOnlyProblems
    if (ApplicationManager.getApplication().isHeadlessEnvironment() && !TESTING_VIEW) {
      return;
    }
    if (myView == null && !ReadAction.compute(() -> InspectionResultsView.hasProblems(tools, this, new InspectionRVContentProviderImpl())).booleanValue()) {
      return;
    }
    initializeViewIfNeeded().doWhenDone(() -> myView.addTools(tools));
  }
}
