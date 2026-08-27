"use strict";

(() => {
  const SVG_NS = "http://www.w3.org/2000/svg";
  const XHTML_NS = "http://www.w3.org/1999/xhtml";
  const NODE_WIDTH = 224;
  const NODE_HEIGHT = 104;
  const LAYER_GAP = 68;
  const NODE_GAP = 48;
  const GRAPH_PADDING = 72;
  const MIN_SCALE = 0.08;
  const MAX_SCALE = 1.8;
  const PARALLEL_WINDOW_MS = 180;

  class TopologyView {
    constructor(options) {
      this.viewport = options.viewport;
      this.scene = options.scene;
      this.edgeLayer = options.edgeLayer;
      this.nodeLayer = options.nodeLayer;
      this.empty = options.empty;
      this.summary = options.summary;
      this.selection = options.selection;
      this.selectionTitle = options.selectionTitle;
      this.selectionDetail = options.selectionDetail;
      this.locateButton = options.locateButton;
      this.onLocate = options.onLocate;
      this.onContextMenu = options.onContextMenu;

      this.events = new Map();
      this.order = [];
      this.nodeElements = new Map();
      this.positions = new Map();
      this.edges = [];
      this.bounds = null;
      this.selectedKey = null;
      this.pendingFocusKey = null;
      this.visible = false;
      this.autoFollow = true;
      this.needsInitialFit = true;
      this.renderFrame = null;
      this.transform = { x: 0, y: 0, scale: 1 };
      this.panState = null;

      this.bindCanvasEvents();
      this.locateButton.addEventListener("click", () => {
        if (this.selectedKey && this.onLocate) {
          this.onLocate(this.selectedKey);
        }
      });
    }

    clear() {
      this.events.clear();
      this.order = [];
      this.positions.clear();
      this.edges = [];
      this.bounds = null;
      this.selectedKey = null;
      this.pendingFocusKey = null;
      this.needsInitialFit = true;
      this.selection.classList.add("hidden");
      this.scheduleRender();
    }

    setEvents(entries) {
      this.events.clear();
      this.order = [];
      this.positions.clear();
      for (const entry of entries) {
        this.storeEvent(entry.key, entry.event);
      }
      this.selectedKey = null;
      this.pendingFocusKey = null;
      this.needsInitialFit = true;
      this.selection.classList.add("hidden");
      this.scheduleRender();
    }

    upsert(key, event) {
      const isNew = !this.events.has(key);
      this.storeEvent(key, event);
      if (isNew && this.autoFollow) {
        this.pendingFocusKey = key;
      }
      this.scheduleRender();
    }

    storeEvent(key, event) {
      const previous = this.events.get(key);
      if (!previous) {
        this.order.push(key);
      }
      const normalized = {
        ...(previous || {}),
        ...event,
        task_id: event.task_id || previous?.task_id || key,
        parent_task_id:
          previous?.parent_task_id || event.parent_task_id || null,
        started_elapsed_ms: numberOr(
          previous?.started_elapsed_ms,
          event.started_elapsed_ms,
          event.elapsed_ms,
          0,
        ),
        initial_event_type:
          previous?.initial_event_type ||
          event.initial_event_type ||
          event.event_type ||
          "activity",
        metrics: event.metrics || previous?.metrics || {},
      };
      this.events.set(key, normalized);
    }

    setVisible(visible) {
      this.visible = visible;
      if (!visible) {
        return;
      }
      this.scheduleRender();
      window.requestAnimationFrame(() => {
        if (this.needsInitialFit) {
          this.fit();
        }
      });
    }

    setAutoFollow(enabled) {
      this.autoFollow = Boolean(enabled);
      if (!this.autoFollow) {
        this.pendingFocusKey = null;
      }
    }

    zoomBy(factor) {
      const rect = this.viewport.getBoundingClientRect();
      this.zoomAt(
        this.transform.scale * factor,
        rect.width / 2,
        rect.height / 2,
      );
    }

    fit() {
      if (!this.bounds) {
        return;
      }
      const rect = this.viewport.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        return;
      }
      const availableWidth = Math.max(1, rect.width - 72);
      const availableHeight = Math.max(1, rect.height - 72);
      const scale = clamp(
        Math.min(
          availableWidth / this.bounds.width,
          availableHeight / this.bounds.height,
          1.05,
        ),
        MIN_SCALE,
        MAX_SCALE,
      );
      this.transform = {
        scale,
        x:
          (rect.width - this.bounds.width * scale) / 2 -
          this.bounds.x * scale,
        y:
          (rect.height - this.bounds.height * scale) / 2 -
          this.bounds.y * scale,
      };
      this.needsInitialFit = false;
      this.applyTransform();
    }

    focusNode(key, { select = true } = {}) {
      if (select) {
        this.selectNode(key);
      }
      const position = this.positions.get(key);
      if (!position) {
        this.pendingFocusKey = key;
        this.scheduleRender();
        return;
      }
      const rect = this.viewport.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        return;
      }
      const scale = clamp(Math.max(this.transform.scale, 0.72), MIN_SCALE, 1.1);
      this.transform = {
        scale,
        x: rect.width / 2 - (position.x + NODE_WIDTH / 2) * scale,
        y: rect.height * 0.58 - (position.y + NODE_HEIGHT / 2) * scale,
      };
      this.applyTransform();
    }

    selectNode(key) {
      if (!this.events.has(key)) {
        return;
      }
      this.selectedKey = key;
      const event = this.events.get(key);
      this.selectionTitle.textContent =
        event.title || event.event_type || "进度节点";
      this.selectionDetail.textContent =
        event.detail ||
        [event.agent_id, friendlyStage(event.stage)].filter(Boolean).join(" · ");
      this.selection.classList.remove("hidden");
      this.scheduleRender();
    }

    scheduleRender() {
      if (this.renderFrame !== null) {
        return;
      }
      this.renderFrame = window.requestAnimationFrame(() => {
        this.renderFrame = null;
        this.render();
      });
    }

    render() {
      const nodes = this.buildNodes();
      this.edgeLayer.replaceChildren();
      if (!nodes.length) {
        this.removeStaleNodes(new Set());
        this.positions.clear();
        this.edges = [];
        this.bounds = null;
        this.empty.classList.remove("hidden");
        this.summary.textContent = "0 个节点 · 0 条连接";
        return;
      }

      this.empty.classList.add("hidden");
      const edges = this.buildEdges(nodes);
      const layout = this.layout(nodes, edges);
      this.positions = layout.positions;
      this.edges = edges;
      this.bounds = layout.bounds;

      const selectedPath = this.selectedPath(edges);
      for (const edge of edges) {
        this.renderEdge(edge, selectedPath);
      }
      for (const node of nodes) {
        this.renderNode(node);
      }
      this.removeStaleNodes(new Set(nodes.map((node) => node.id)));

      const running = nodes.filter((node) => node.status === "running").length;
      this.summary.textContent =
        `${nodes.length} 个节点 · ${edges.length} 条连接` +
        (running ? ` · ${running} 个运行中` : "");
      this.applyTransform();

      if (this.needsInitialFit && this.visible) {
        window.requestAnimationFrame(() => this.fit());
      } else if (this.pendingFocusKey && this.visible && this.autoFollow) {
        const key = this.pendingFocusKey;
        this.pendingFocusKey = null;
        window.requestAnimationFrame(() => {
          this.focusNode(key, { select: false });
        });
      }
    }

    removeStaleNodes(activeKeys) {
      for (const [key, elements] of this.nodeElements) {
        if (activeKeys.has(key)) {
          continue;
        }
        elements.group.remove();
        this.nodeElements.delete(key);
      }
    }

    buildNodes() {
      const taskKeys = new Map();
      for (const key of this.order) {
        const event = this.events.get(key);
        if (event?.task_id) {
          taskKeys.set(String(event.task_id), key);
        }
      }
      const preliminary = this.order
        .map((key, index) => {
          const event = this.events.get(key);
          if (!event) {
            return null;
          }
          return {
            id: key,
            order: index,
            event,
            status: String(event.status || "info"),
            kind: nodeKind(event),
            started: numberOr(event.started_elapsed_ms, event.elapsed_ms, index),
            finished: Math.max(
              numberOr(event.elapsed_ms, event.started_elapsed_ms, index),
              numberOr(event.started_elapsed_ms, index),
            ),
            relationKeys: relationKeys(event),
            parentId: event.parent_task_id
              ? taskKeys.get(String(event.parent_task_id)) || null
              : null,
          };
        })
        .filter(Boolean);
      const terminal = [...preliminary].reverse().find(isTerminalNode);
      if (terminal) {
        const settledStatus = ["failed", "cancelled"].includes(terminal.status)
          ? "failed"
          : "completed";
        for (const node of preliminary) {
          if (node.order < terminal.order && node.status === "running") {
            node.status = settledStatus;
          }
        }
      }
      const root = preliminary.find((node) => node.kind === "run") || preliminary[0];
      if (root) {
        for (const node of preliminary) {
          if (node.id !== root.id && !node.parentId) {
            node.parentId = root.id;
          }
        }
      }
      return preliminary;
    }

    buildEdges(nodes) {
      const nodeMap = new Map(nodes.map((node) => [node.id, node]));
      const children = new Map();
      for (const node of nodes) {
        if (!node.parentId || !nodeMap.has(node.parentId)) {
          continue;
        }
        if (!children.has(node.parentId)) {
          children.set(node.parentId, []);
        }
        children.get(node.parentId).push(node);
      }
      const roots = nodes.filter((node) => !node.parentId || !nodeMap.has(node.parentId));
      const edges = [];
      const edgeKeys = new Set();
      const terminals = new Map();
      const visiting = new Set();

      const addEdge = (source, target) => {
        if (!source || !target || source === target) {
          return;
        }
        const key = `${source}->${target}`;
        if (edgeKeys.has(key)) {
          return;
        }
        edgeKeys.add(key);
        edges.push({ source, target });
      };

      const buildGroup = (parentId) => {
        if (visiting.has(parentId)) {
          terminals.set(parentId, [parentId]);
          return;
        }
        visiting.add(parentId);
        const group = [...(children.get(parentId) || [])].sort(compareNodes);
        for (const child of group) {
          buildGroup(child.id);
        }

        const continued = new Set();
        for (let index = 0; index < group.length; index += 1) {
          const node = group[index];
          const predecessor = bestPredecessor(node, group.slice(0, index));
          if (!predecessor) {
            addEdge(parentId, node.id);
            continue;
          }
          const sources = terminals.get(predecessor.id) || [predecessor.id];
          for (const source of sources) {
            addEdge(source, node.id);
          }
          continued.add(predecessor.id);
        }

        const terminalIds = [];
        for (const child of group) {
          if (continued.has(child.id)) {
            continue;
          }
          terminalIds.push(...(terminals.get(child.id) || [child.id]));
        }
        terminals.set(
          parentId,
          terminalIds.length ? [...new Set(terminalIds)] : [parentId],
        );
        visiting.delete(parentId);
      };

      for (const root of roots) {
        buildGroup(root.id);
      }
      for (const node of nodes) {
        if (!terminals.has(node.id)) {
          buildGroup(node.id);
        }
      }
      return edges;
    }

    layout(nodes, edges) {
      const nodeMap = new Map(nodes.map((node) => [node.id, node]));
      const outgoing = new Map(nodes.map((node) => [node.id, []]));
      const indegree = new Map(nodes.map((node) => [node.id, 0]));
      const depth = new Map(nodes.map((node) => [node.id, 0]));
      for (const edge of edges) {
        if (!nodeMap.has(edge.source) || !nodeMap.has(edge.target)) {
          continue;
        }
        outgoing.get(edge.source).push(edge.target);
        indegree.set(edge.target, indegree.get(edge.target) + 1);
      }

      const ready = nodes
        .filter((node) => indegree.get(node.id) === 0)
        .sort((a, b) => a.order - b.order);
      const visited = new Set();
      while (ready.length) {
        const node = ready.shift();
        if (visited.has(node.id)) {
          continue;
        }
        visited.add(node.id);
        for (const targetId of outgoing.get(node.id) || []) {
          depth.set(
            targetId,
            Math.max(depth.get(targetId), depth.get(node.id) + 1),
          );
          indegree.set(targetId, indegree.get(targetId) - 1);
          if (indegree.get(targetId) === 0) {
            ready.push(nodeMap.get(targetId));
            ready.sort((a, b) => a.order - b.order);
          }
        }
      }

      let fallbackDepth = Math.max(0, ...depth.values());
      for (const node of nodes) {
        if (!visited.has(node.id)) {
          fallbackDepth += 1;
          depth.set(node.id, fallbackDepth);
        }
      }

      const layers = new Map();
      for (const node of nodes) {
        const layer = depth.get(node.id);
        if (!layers.has(layer)) {
          layers.set(layer, []);
        }
        layers.get(layer).push(node);
      }
      for (const layer of layers.values()) {
        layer.sort((a, b) => a.order - b.order);
      }

      const widestLayer = Math.max(
        1,
        ...[...layers.values()].map((layer) => layer.length),
      );
      const graphWidth =
        GRAPH_PADDING * 2 +
        widestLayer * NODE_WIDTH +
        Math.max(0, widestLayer - 1) * NODE_GAP;
      const maxDepth = Math.max(0, ...layers.keys());
      const graphHeight =
        GRAPH_PADDING * 2 +
        (maxDepth + 1) * NODE_HEIGHT +
        maxDepth * LAYER_GAP;
      const positions = new Map();

      for (const [layerIndex, layer] of layers) {
        const layerWidth =
          layer.length * NODE_WIDTH +
          Math.max(0, layer.length - 1) * NODE_GAP;
        const startX = (graphWidth - layerWidth) / 2;
        const y = GRAPH_PADDING + layerIndex * (NODE_HEIGHT + LAYER_GAP);
        layer.forEach((node, index) => {
          positions.set(node.id, {
            x: startX + index * (NODE_WIDTH + NODE_GAP),
            y,
          });
        });
      }

      return {
        positions,
        bounds: {
          x: 0,
          y: 0,
          width: graphWidth,
          height: graphHeight,
        },
      };
    }

    renderEdge(edge, selectedPath) {
      const source = this.positions.get(edge.source);
      const target = this.positions.get(edge.target);
      if (!source || !target) {
        return;
      }
      const startX = source.x + NODE_WIDTH / 2;
      const startY = source.y + NODE_HEIGHT;
      const endX = target.x + NODE_WIDTH / 2;
      const endY = target.y;
      const middleY = startY + Math.max(28, (endY - startY) / 2);
      const path = document.createElementNS(SVG_NS, "path");
      path.setAttribute(
        "d",
        `M ${startX} ${startY} C ${startX} ${middleY}, ` +
          `${endX} ${middleY}, ${endX} ${endY}`,
      );
      const active =
        selectedPath.has(`${edge.source}->${edge.target}`) ||
        this.events.get(edge.target)?.status === "running";
      path.setAttribute("class", `topology-edge${active ? " active" : ""}`);
      this.edgeLayer.append(path);
    }

    renderNode(node) {
      const position = this.positions.get(node.id);
      if (!position) {
        return;
      }
      let elements = this.nodeElements.get(node.id);
      if (!elements) {
        const group = document.createElementNS(SVG_NS, "g");
        const foreignObject = document.createElementNS(SVG_NS, "foreignObject");
        foreignObject.setAttribute("width", String(NODE_WIDTH));
        foreignObject.setAttribute("height", String(NODE_HEIGHT));
        const card = htmlElement("div", "topology-node-card");
        const head = htmlElement("div", "topology-node-head");
        const status = htmlElement("span", "topology-node-status");
        const title = htmlElement("span", "topology-node-title");
        const time = htmlElement("span", "topology-node-time");
        const detail = htmlElement("div", "topology-node-detail");
        const meta = htmlElement("div", "topology-node-meta");
        const agent = htmlElement("span", "topology-node-agent");
        const stage = htmlElement("span", "topology-node-stage");
        head.append(status, title, time);
        meta.append(agent, stage);
        card.append(head, detail, meta);
        foreignObject.append(card);
        group.append(foreignObject);
        group.addEventListener("click", (event) => {
          event.stopPropagation();
          this.selectNode(group.dataset.nodeKey);
        });
        group.addEventListener("dblclick", (event) => {
          event.stopPropagation();
          this.focusNode(group.dataset.nodeKey);
        });
        group.addEventListener("contextmenu", (event) => {
          event.preventDefault();
          event.stopPropagation();
          const key = group.dataset.nodeKey;
          this.selectNode(key);
          if (this.onContextMenu) {
            this.onContextMenu({
              key,
              event: this.events.get(key),
              clientX: event.clientX,
              clientY: event.clientY,
            });
          }
        });
        elements = { group, card, title, time, detail, agent, stage };
        this.nodeElements.set(node.id, elements);
        this.nodeLayer.append(group);
      }

      elements.group.dataset.nodeKey = node.id;
      elements.group.setAttribute(
        "class",
        `topology-node${node.id === this.selectedKey ? " selected" : ""}`,
      );
      elements.group.setAttribute(
        "transform",
        `translate(${position.x} ${position.y})`,
      );
      elements.group.setAttribute(
        "aria-label",
        [node.event.title, node.event.detail, node.event.agent_id]
          .filter(Boolean)
          .join(". "),
      );
      elements.card.setAttribute(
        "class",
        `topology-node-card ${statusClass(node.status)} kind-${node.kind}`,
      );
      elements.title.textContent =
        node.event.title || node.event.event_type || "进度节点";
      elements.time.textContent = formatElapsed(taskDuration(node.event));
      elements.detail.textContent = node.event.detail || nodeTypeLabel(node);
      elements.agent.textContent = node.event.agent_id || nodeTypeLabel(node);
      elements.stage.textContent = friendlyStage(node.event.stage);
    }

    tick(now = Date.now()) {
      for (const [key, elements] of this.nodeElements.entries()) {
        const event = this.events.get(key);
        if (event?.status === "running") {
          elements.time.textContent = formatElapsed(taskDuration(event, now));
        }
      }
    }

    selectedPath(edges) {
      const selectedEdges = new Set();
      if (!this.selectedKey) {
        return selectedEdges;
      }
      const incoming = new Map();
      for (const edge of edges) {
        if (!incoming.has(edge.target)) {
          incoming.set(edge.target, []);
        }
        incoming.get(edge.target).push(edge);
      }
      const pending = [this.selectedKey];
      const seen = new Set();
      while (pending.length) {
        const target = pending.pop();
        if (seen.has(target)) {
          continue;
        }
        seen.add(target);
        for (const edge of incoming.get(target) || []) {
          selectedEdges.add(`${edge.source}->${edge.target}`);
          pending.push(edge.source);
        }
      }
      return selectedEdges;
    }

    bindCanvasEvents() {
      this.viewport.addEventListener(
        "wheel",
        (event) => {
          event.preventDefault();
          const rect = this.viewport.getBoundingClientRect();
          const factor = event.deltaY < 0 ? 1.12 : 0.89;
          this.zoomAt(
            this.transform.scale * factor,
            event.clientX - rect.left,
            event.clientY - rect.top,
          );
        },
        { passive: false },
      );
      this.viewport.addEventListener("pointerdown", (event) => {
        if (
          event.button !== 0 ||
          event.target.closest?.(".topology-node-card")
        ) {
          return;
        }
        this.viewport.setPointerCapture(event.pointerId);
        this.panState = {
          pointerId: event.pointerId,
          startX: event.clientX,
          startY: event.clientY,
          originX: this.transform.x,
          originY: this.transform.y,
        };
        this.viewport.classList.add("panning");
      });
      this.viewport.addEventListener("pointermove", (event) => {
        if (!this.panState || this.panState.pointerId !== event.pointerId) {
          return;
        }
        this.transform.x =
          this.panState.originX + event.clientX - this.panState.startX;
        this.transform.y =
          this.panState.originY + event.clientY - this.panState.startY;
        this.applyTransform();
      });
      const endPan = (event) => {
        if (!this.panState || this.panState.pointerId !== event.pointerId) {
          return;
        }
        this.panState = null;
        this.viewport.classList.remove("panning");
      };
      this.viewport.addEventListener("pointerup", endPan);
      this.viewport.addEventListener("pointercancel", endPan);
      this.viewport.addEventListener("click", (event) => {
        if (event.target === this.viewport || event.target === this.scene.ownerSVGElement) {
          this.selectedKey = null;
          this.selection.classList.add("hidden");
          this.scheduleRender();
        }
      });
    }

    zoomAt(nextScale, screenX, screenY) {
      const scale = clamp(nextScale, MIN_SCALE, MAX_SCALE);
      const graphX = (screenX - this.transform.x) / this.transform.scale;
      const graphY = (screenY - this.transform.y) / this.transform.scale;
      this.transform = {
        scale,
        x: screenX - graphX * scale,
        y: screenY - graphY * scale,
      };
      this.needsInitialFit = false;
      this.applyTransform();
    }

    applyTransform() {
      this.scene.setAttribute(
        "transform",
        `translate(${this.transform.x} ${this.transform.y}) ` +
          `scale(${this.transform.scale})`,
      );
    }
  }

  function bestPredecessor(node, candidates) {
    if (isTerminalNode(node)) {
      return (
        candidates
          .filter((candidate) => candidate.finished <= node.started + 120)
          .sort(compareNodes)
          .at(-1) || null
      );
    }
    let best = null;
    let bestScore = Number.NEGATIVE_INFINITY;
    for (const candidate of candidates) {
      if (candidate.finished > node.started + 120) {
        continue;
      }
      const relation = relationScore(candidate, node);
      const sameWave =
        Math.abs(candidate.started - node.started) <= PARALLEL_WINDOW_MS;
      if (sameWave && relation < 350) {
        continue;
      }
      const gap = Math.max(0, node.started - candidate.finished);
      const score =
        relation +
        Math.max(-250, 420 - gap / 220) +
        candidate.order / 10000;
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  function relationScore(source, target) {
    let score = 0;
    const overlap = [...source.relationKeys].filter((key) =>
      target.relationKeys.has(key),
    ).length;
    score += overlap * 900;
    if (
      source.event.agent_id &&
      source.event.agent_id === target.event.agent_id
    ) {
      score += 180;
    }
    if (source.event.stage && source.event.stage === target.event.stage) {
      score += 55;
    }
    if (
      String(source.event.event_type || "").includes("admitted") &&
      target.kind === "agent"
    ) {
      score += 420;
    }
    if (
      String(target.event.event_type || "").includes("checkpoint") &&
      source.event.stage === "checkpoint_verification"
    ) {
      score += 360;
    }
    return score;
  }

  function compareNodes(a, b) {
    return (
      a.started - b.started ||
      a.finished - b.finished ||
      a.order - b.order
    );
  }

  function relationKeys(event) {
    const metrics = event.metrics || {};
    const keys = new Set();
    for (const name of [
      "route_id",
      "path_id",
      "strategy_id",
      "attempt_id",
      "checkpoint_id",
      "target_id",
      "experiment_id",
      "request_hash",
    ]) {
      if (metrics[name]) {
        keys.add(`${name}:${metrics[name]}`);
      }
    }
    return keys;
  }

  function nodeKind(event) {
    const initialType = String(event.initial_event_type || event.event_type || "");
    const taskId = String(event.task_id || "");
    if (
      ["run", "run_resume", "run_started", "run_failed", "run_cancelled"].includes(
        initialType,
      )
    ) {
      return "run";
    }
    if (
      [
        "stage",
        "adaptive_round",
        "final_revision",
        "proof_continuation",
      ].includes(initialType)
    ) {
      return "stage";
    }
    if (String(event.event_type || "").includes("checkpoint")) {
      return "checkpoint";
    }
    if (
      String(event.event_type || "").includes("message") ||
      String(event.stage || "") === "message_broker"
    ) {
      return "message";
    }
    if (
      taskId.startsWith("computation:") ||
      [
        "python_experiment",
        "computation_experiment",
        "computation_decision",
        "experiment_completed",
      ].includes(initialType)
    ) {
      return "computation";
    }
    if (initialType === "agent_call" || event.agent_id) {
      return "agent";
    }
    return "event";
  }

  function isTerminalNode(node) {
    const type = String(node.event.event_type || "");
    const stage = String(node.event.stage || "");
    return (
      ["run_failed", "run_cancelled", "result"].includes(type) ||
      (stage === "report" && ["failed", "cancelled", "completed"].includes(node.status))
    );
  }

  function nodeTypeLabel(node) {
    return {
      run: "运行",
      stage: "阶段",
      agent: "Agent",
      checkpoint: "检查点",
      message: "通信",
      computation: "计算",
      event: "调度",
    }[node.kind];
  }

  function statusClass(status) {
    return ["running", "completed", "warning", "failed"].includes(status)
      ? status
      : "info";
  }

  function friendlyStage(stage) {
    return String(stage || "")
      .replaceAll("_", " ")
      .trim();
  }

  function htmlElement(tag, className, text = "") {
    const element = document.createElementNS(XHTML_NS, tag);
    element.setAttribute("class", className);
    if (text) {
      element.textContent = text;
    }
    return element;
  }

  function formatElapsed(milliseconds) {
    const totalSeconds = Math.max(
      0,
      Math.floor(numberOr(milliseconds, 0) / 1000),
    );
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
    }
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }

  function taskDuration(event, now = Date.now()) {
    const started = numberOr(event?.started_elapsed_ms, event?.elapsed_ms, 0);
    const elapsed = numberOr(event?.elapsed_ms, started);
    if (event?.status !== "running") {
      return Math.max(0, elapsed - started);
    }
    const observedAt = Date.parse(event?.timestamp || "");
    if (Number.isNaN(observedAt)) {
      return Math.max(0, elapsed - started);
    }
    const taskStartedAt = observedAt - Math.max(0, elapsed - started);
    return Math.max(0, now - taskStartedAt);
  }

  function numberOr(...values) {
    for (const value of values) {
      if (typeof value === "number" && Number.isFinite(value)) {
        return value;
      }
    }
    return 0;
  }

  function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }

  window.MathProofMeshTopology = TopologyView;
})();
