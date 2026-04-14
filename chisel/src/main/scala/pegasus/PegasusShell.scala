package pegasus

import chisel3._
import chisel3.util.HasBlackBoxInline

// PegasusShell: inline Verilog BlackBox wrapping XDMA + HBM2 + SCU.
//
// Architecture:
//   XDMA m_axib (512-bit, 32-bit addr)  →  axi_dwidth_converter_h2c (512→256, 32-bit addr)
//                                        →  {1'b0, m_axi_awaddr} → HBM2 AXI_00 (33-bit addr)
//   XDMA s_axil (BAR0 AXI-Lite)         →  SCU  (ctrl[0] = cpu_hold_reset)
//   chip_mem (SoC, 256-bit, 33-bit addr) →  HBM2 AXI_16 (MC01, 33-bit addr)
//
// SCU register map (BAR0):
//   0x000  ctrl[31:0]  bit0=cpu_hold_reset  (reset value: 1 — CPU held until driver writes 0)
class PegasusShell extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    // PCIe
    val pcie_sys_clk    = Input(Clock())
    val pcie_sys_clk_gt = Input(Clock())
    val pcie_sys_rst_n  = Input(Bool())
    val pcie_exp_txp    = Output(UInt(16.W))
    val pcie_exp_txn    = Output(UInt(16.W))
    val pcie_exp_rxp    = Input(UInt(16.W))
    val pcie_exp_rxn    = Input(UInt(16.W))
    val hbm_ref_clk     = Input(Clock())

    // SoC clock/reset (from XDMA axi_aclk / SCU)
    val dut_clk   = Output(Clock())
    val dut_reset = Output(Bool())   // active-high: ~axi_aresetn | cpu_hold_reset

    // SoC UART
    val uart_tx = Input(Bool())

    // SoC mem_axi4 → HBM2 AXI_16 (256-bit data, 33-bit addr)
    val chip_mem_awid    = Input(UInt(6.W))
    val chip_mem_awaddr  = Input(UInt(33.W))
    val chip_mem_awlen   = Input(UInt(8.W))
    val chip_mem_awsize  = Input(UInt(3.W))
    val chip_mem_awburst = Input(UInt(2.W))
    val chip_mem_awvalid = Input(Bool())
    val chip_mem_awready = Output(Bool())

    val chip_mem_wdata  = Input(UInt(256.W))
    val chip_mem_wstrb  = Input(UInt(32.W))
    val chip_mem_wlast  = Input(Bool())
    val chip_mem_wvalid = Input(Bool())
    val chip_mem_wready = Output(Bool())

    val chip_mem_bid    = Output(UInt(6.W))
    val chip_mem_bresp  = Output(UInt(2.W))
    val chip_mem_bvalid = Output(Bool())
    val chip_mem_bready = Input(Bool())

    val chip_mem_arid    = Input(UInt(6.W))
    val chip_mem_araddr  = Input(UInt(33.W))
    val chip_mem_arlen   = Input(UInt(8.W))
    val chip_mem_arsize  = Input(UInt(3.W))
    val chip_mem_arburst = Input(UInt(2.W))
    val chip_mem_arvalid = Input(Bool())
    val chip_mem_arready = Output(Bool())

    val chip_mem_rid    = Output(UInt(6.W))
    val chip_mem_rdata  = Output(UInt(256.W))
    val chip_mem_rresp  = Output(UInt(2.W))
    val chip_mem_rlast  = Output(Bool())
    val chip_mem_rvalid = Output(Bool())
    val chip_mem_rready = Input(Bool())
  })

  setInline("PegasusShell.v",
    """module PegasusShell (
      |  input         pcie_sys_clk,
      |  input         pcie_sys_clk_gt,
      |  input         pcie_sys_rst_n,
      |  output [15:0] pcie_exp_txp,
      |  output [15:0] pcie_exp_txn,
      |  input  [15:0] pcie_exp_rxp,
      |  input  [15:0] pcie_exp_rxn,
      |  input         hbm_ref_clk,
      |  output        dut_clk,
      |  output        dut_reset,
      |  input         uart_tx,
      |  input  [5:0]   chip_mem_awid,
      |  input  [32:0]  chip_mem_awaddr,
      |  input  [7:0]   chip_mem_awlen,
      |  input  [2:0]   chip_mem_awsize,
      |  input  [1:0]   chip_mem_awburst,
      |  input          chip_mem_awvalid,
      |  output         chip_mem_awready,
      |  input  [255:0] chip_mem_wdata,
      |  input  [31:0]  chip_mem_wstrb,
      |  input          chip_mem_wlast,
      |  input          chip_mem_wvalid,
      |  output         chip_mem_wready,
      |  output [5:0]   chip_mem_bid,
      |  output [1:0]   chip_mem_bresp,
      |  output         chip_mem_bvalid,
      |  input          chip_mem_bready,
      |  input  [5:0]   chip_mem_arid,
      |  input  [32:0]  chip_mem_araddr,
      |  input  [7:0]   chip_mem_arlen,
      |  input  [2:0]   chip_mem_arsize,
      |  input  [1:0]   chip_mem_arburst,
      |  input          chip_mem_arvalid,
      |  output         chip_mem_arready,
      |  output [5:0]   chip_mem_rid,
      |  output [255:0] chip_mem_rdata,
      |  output [1:0]   chip_mem_rresp,
      |  output         chip_mem_rlast,
      |  output         chip_mem_rvalid,
      |  input          chip_mem_rready
      |);
      |
      |  wire axi_aclk;
      |  wire axi_aresetn;
      |  wire pcie_sys_clk_gt_buf;
      |
      |  // ── XDMA m_axib wires (512-bit, 32-bit addr) ─────────────────────
      |  wire [3:0]   xdma_awid;
      |  wire [31:0]  xdma_awaddr;
      |  wire [7:0]   xdma_awlen;
      |  wire [2:0]   xdma_awsize;
      |  wire [1:0]   xdma_awburst;
      |  wire         xdma_awvalid, xdma_awready;
      |  wire [511:0] xdma_wdata;
      |  wire [63:0]  xdma_wstrb;
      |  wire         xdma_wlast, xdma_wvalid, xdma_wready;
      |  wire [3:0]   xdma_bid;
      |  wire [1:0]   xdma_bresp;
      |  wire         xdma_bvalid, xdma_bready;
      |  wire [3:0]   xdma_arid;
      |  wire [31:0]  xdma_araddr;
      |  wire [7:0]   xdma_arlen;
      |  wire [2:0]   xdma_arsize;
      |  wire [1:0]   xdma_arburst;
      |  wire         xdma_arvalid, xdma_arready;
      |  wire [3:0]   xdma_rid;
      |  wire [511:0] xdma_rdata;
      |  wire [1:0]   xdma_rresp;
      |  wire         xdma_rlast, xdma_rvalid, xdma_rready;
      |
      |  // ── XDMA s_axil wires (BAR0, 32-bit) ────────────────────────────
      |  wire [31:0] axil_awaddr;
      |  wire [2:0]  axil_awprot;
      |  wire        axil_awvalid, axil_awready;
      |  wire [31:0] axil_wdata;
      |  wire [3:0]  axil_wstrb;
      |  wire        axil_wvalid, axil_wready;
      |  wire        axil_bvalid;
      |  wire [1:0]  axil_bresp;
      |  wire        axil_bready;
      |  wire [31:0] axil_araddr;
      |  wire [2:0]  axil_arprot;
      |  wire        axil_arvalid, axil_arready;
      |  wire [31:0] axil_rdata;
      |  wire [1:0]  axil_rresp;
      |  wire        axil_rvalid, axil_rready;
      |
      |  // ── dwidth converter master → HBM2 AXI_00 (256-bit, 32-bit addr out) ──
      |  // Master addr is 32-bit; zero-extend to 33-bit when connecting to HBM2.
      |  // Master len is 8-bit (AXI4); truncate to [3:0] for HBM2 (max burst=16).
      |  // No separate m_axi_aclk/aresetn — single-clock mode uses s_axi_aclk.
      |  wire [31:0]  dw_awaddr;
      |  wire [7:0]   dw_awlen;
      |  wire [2:0]   dw_awsize;
      |  wire [1:0]   dw_awburst;
      |  wire         dw_awvalid, dw_awready;
      |  wire [255:0] dw_wdata;
      |  wire [31:0]  dw_wstrb;
      |  wire         dw_wlast, dw_wvalid, dw_wready;
      |  wire [1:0]   dw_bresp;
      |  wire         dw_bvalid, dw_bready;
      |  wire [31:0]  dw_araddr;
      |  wire [7:0]   dw_arlen;
      |  wire [2:0]   dw_arsize;
      |  wire [1:0]   dw_arburst;
      |  wire         dw_arvalid, dw_arready;
      |  wire [255:0] dw_rdata;
      |  wire [1:0]   dw_rresp;
      |  wire         dw_rlast, dw_rvalid, dw_rready;
      |
      |  // ── SCU ──────────────────────────────────────────────────────────
      |  wire cpu_hold_reset;
      |
      |  // ── IBUFDS_GTE4 ──────────────────────────────────────────────────
      |  IBUFDS_GTE4 #(
      |    .REFCLK_EN_TX_PATH(1'b0), .REFCLK_HROW_CK_SEL(2'b00), .REFCLK_ICNTL_RX(2'b00)
      |  ) ibufds_gt_clk (
      |    .I(pcie_sys_clk_gt), .IB(1'b0), .CEB(1'b0), .O(pcie_sys_clk_gt_buf), .ODIV2()
      |  );
      |
      |  // ── XDMA ─────────────────────────────────────────────────────────
      |  xdma_0 xdma (
      |    .sys_clk(pcie_sys_clk), .sys_clk_gt(pcie_sys_clk_gt_buf),
      |    .sys_rst_n(pcie_sys_rst_n),
      |    .pci_exp_txp(pcie_exp_txp), .pci_exp_txn(pcie_exp_txn),
      |    .pci_exp_rxp(pcie_exp_rxp), .pci_exp_rxn(pcie_exp_rxn),
      |    .axi_aclk(axi_aclk), .axi_aresetn(axi_aresetn),
      |    .usr_irq_req(1'b0),
      |    // m_axib
      |    .m_axib_awid(xdma_awid),     .m_axib_awaddr(xdma_awaddr),
      |    .m_axib_awlen(xdma_awlen),   .m_axib_awsize(xdma_awsize),
      |    .m_axib_awburst(xdma_awburst), .m_axib_awprot(),
      |    .m_axib_awvalid(xdma_awvalid),.m_axib_awready(xdma_awready),
      |    .m_axib_awlock(), .m_axib_awcache(),
      |    .m_axib_wdata(xdma_wdata),   .m_axib_wstrb(xdma_wstrb),
      |    .m_axib_wlast(xdma_wlast),   .m_axib_wvalid(xdma_wvalid),
      |    .m_axib_wready(xdma_wready),
      |    .m_axib_bid(xdma_bid),       .m_axib_bresp(xdma_bresp),
      |    .m_axib_bvalid(xdma_bvalid), .m_axib_bready(xdma_bready),
      |    .m_axib_arid(xdma_arid),     .m_axib_araddr(xdma_araddr),
      |    .m_axib_arlen(xdma_arlen),   .m_axib_arsize(xdma_arsize),
      |    .m_axib_arburst(xdma_arburst),.m_axib_arprot(),
      |    .m_axib_arvalid(xdma_arvalid),.m_axib_arready(xdma_arready),
      |    .m_axib_arlock(), .m_axib_arcache(),
      |    .m_axib_rid(xdma_rid),       .m_axib_rdata(xdma_rdata),
      |    .m_axib_rresp(xdma_rresp),   .m_axib_rlast(xdma_rlast),
      |    .m_axib_rvalid(xdma_rvalid), .m_axib_rready(xdma_rready),
      |    // s_axil (BAR0 → SCU)
      |    .s_axil_awaddr(axil_awaddr),   .s_axil_awprot(axil_awprot),
      |    .s_axil_awvalid(axil_awvalid), .s_axil_awready(axil_awready),
      |    .s_axil_wdata(axil_wdata),     .s_axil_wstrb(axil_wstrb),
      |    .s_axil_wvalid(axil_wvalid),   .s_axil_wready(axil_wready),
      |    .s_axil_bvalid(axil_bvalid),   .s_axil_bresp(axil_bresp),
      |    .s_axil_bready(axil_bready),
      |    .s_axil_araddr(axil_araddr),   .s_axil_arprot(axil_arprot),
      |    .s_axil_arvalid(axil_arvalid), .s_axil_arready(axil_arready),
      |    .s_axil_rdata(axil_rdata),     .s_axil_rresp(axil_rresp),
      |    .s_axil_rvalid(axil_rvalid),   .s_axil_rready(axil_rready),
      |    // s_axib unused
      |    .s_axib_awid(4'h0),    .s_axib_awaddr(32'h0), .s_axib_awregion(4'h0),
      |    .s_axib_awlen(8'h0),   .s_axib_awsize(3'h0),  .s_axib_awburst(2'h0),
      |    .s_axib_awvalid(1'b0), .s_axib_wdata(512'h0), .s_axib_wstrb(64'h0),
      |    .s_axib_wlast(1'b0),   .s_axib_wvalid(1'b0),  .s_axib_bready(1'b0),
      |    .s_axib_arid(4'h0),    .s_axib_araddr(32'h0), .s_axib_arregion(4'h0),
      |    .s_axib_arlen(8'h0),   .s_axib_arsize(3'h0),  .s_axib_arburst(2'h0),
      |    .s_axib_arvalid(1'b0), .s_axib_rready(1'b0)
      |  );
      |
      |  assign dut_clk   = axi_aclk;
      |  assign dut_reset = ~axi_aresetn | cpu_hold_reset;
      |
      |  // ── SCU — AXI-Lite slave, 1 register ─────────────────────────────
      |  reg [31:0] scu_ctrl;      // bit0 = cpu_hold_reset, reset=1
      |  reg        scu_aw_pend;
      |  reg        scu_bvalid_r;
      |  reg        scu_rvalid_r;
      |  reg [31:0] scu_rdata_r;
      |
      |  always @(posedge axi_aclk or negedge axi_aresetn) begin
      |    if (!axi_aresetn) begin
      |      scu_ctrl    <= 32'h1;
      |      scu_aw_pend <= 1'b0;
      |      scu_bvalid_r<= 1'b0;
      |      scu_rvalid_r<= 1'b0;
      |    end else begin
      |      if (axil_awvalid && axil_awready) scu_aw_pend <= 1'b1;
      |      if ((scu_aw_pend || (axil_awvalid && axil_awready)) && axil_wvalid) begin
      |        scu_ctrl     <= axil_wdata;
      |        scu_aw_pend  <= 1'b0;
      |        scu_bvalid_r <= 1'b1;
      |      end
      |      if (scu_bvalid_r && axil_bready) scu_bvalid_r <= 1'b0;
      |      if (axil_arvalid && axil_arready) begin
      |        scu_rdata_r  <= scu_ctrl;
      |        scu_rvalid_r <= 1'b1;
      |      end
      |      if (scu_rvalid_r && axil_rready) scu_rvalid_r <= 1'b0;
      |    end
      |  end
      |  assign axil_awready = ~scu_aw_pend & ~scu_bvalid_r;
      |  assign axil_wready  = 1'b1;
      |  assign axil_bvalid  = scu_bvalid_r;
      |  assign axil_bresp   = 2'b00;
      |  assign axil_arready = ~scu_rvalid_r;
      |  assign axil_rdata   = scu_rdata_r;
      |  assign axil_rresp   = 2'b00;
      |  assign axil_rvalid  = scu_rvalid_r;
      |  assign cpu_hold_reset = scu_ctrl[0];
      |
      |  // ── AXI Width Converter: XDMA 512-bit → 256-bit (32-bit addr) ────
      |  // Master side has no ID ports; converter strips IDs internally.
      |  axi_dwidth_converter_h2c dwidth_h2c (
      |    .s_axi_aclk   (axi_aclk),
      |    .s_axi_aresetn(axi_aresetn),
      |    // Slave (XDMA 512-bit, 32-bit addr, 4-bit id)
      |    .s_axi_awid   (xdma_awid),    .s_axi_awaddr (xdma_awaddr),
      |    .s_axi_awlen  (xdma_awlen),   .s_axi_awsize (xdma_awsize),
      |    .s_axi_awburst(xdma_awburst), .s_axi_awlock (1'b0),
      |    .s_axi_awcache(4'h0),         .s_axi_awprot (3'h0),
      |    .s_axi_awregion(4'h0),        .s_axi_awqos  (4'h0),
      |    .s_axi_awvalid(xdma_awvalid), .s_axi_awready(xdma_awready),
      |    .s_axi_wdata  (xdma_wdata),   .s_axi_wstrb  (xdma_wstrb),
      |    .s_axi_wlast  (xdma_wlast),   .s_axi_wvalid (xdma_wvalid),
      |    .s_axi_wready (xdma_wready),
      |    .s_axi_bid    (xdma_bid),     .s_axi_bresp  (xdma_bresp),
      |    .s_axi_bvalid (xdma_bvalid),  .s_axi_bready (xdma_bready),
      |    .s_axi_arid   (xdma_arid),    .s_axi_araddr (xdma_araddr),
      |    .s_axi_arlen  (xdma_arlen),   .s_axi_arsize (xdma_arsize),
      |    .s_axi_arburst(xdma_arburst), .s_axi_arlock (1'b0),
      |    .s_axi_arcache(4'h0),         .s_axi_arprot (3'h0),
      |    .s_axi_arregion(4'h0),        .s_axi_arqos  (4'h0),
      |    .s_axi_arvalid(xdma_arvalid), .s_axi_arready(xdma_arready),
      |    .s_axi_rid    (xdma_rid),     .s_axi_rdata  (xdma_rdata),
      |    .s_axi_rresp  (xdma_rresp),   .s_axi_rlast  (xdma_rlast),
      |    .s_axi_rvalid (xdma_rvalid),  .s_axi_rready (xdma_rready),
      |    // Master (256-bit, 32-bit addr, NO separate clock/reset, NO id ports)
      |    .m_axi_awaddr (dw_awaddr),    .m_axi_awlen  (dw_awlen),
      |    .m_axi_awsize (dw_awsize),    .m_axi_awburst(dw_awburst),
      |    .m_axi_awlock (), .m_axi_awcache(), .m_axi_awprot(),
      |    .m_axi_awregion(), .m_axi_awqos(),
      |    .m_axi_awvalid(dw_awvalid),   .m_axi_awready(dw_awready),
      |    .m_axi_wdata  (dw_wdata),     .m_axi_wstrb  (dw_wstrb),
      |    .m_axi_wlast  (dw_wlast),     .m_axi_wvalid (dw_wvalid),
      |    .m_axi_wready (dw_wready),
      |    .m_axi_bresp  (dw_bresp),     .m_axi_bvalid (dw_bvalid),
      |    .m_axi_bready (dw_bready),
      |    .m_axi_araddr (dw_araddr),    .m_axi_arlen  (dw_arlen),
      |    .m_axi_arsize (dw_arsize),    .m_axi_arburst(dw_arburst),
      |    .m_axi_arlock (), .m_axi_arcache(), .m_axi_arprot(),
      |    .m_axi_arregion(), .m_axi_arqos(),
      |    .m_axi_arvalid(dw_arvalid),   .m_axi_arready(dw_arready),
      |    .m_axi_rdata  (dw_rdata),     .m_axi_rresp  (dw_rresp),
      |    .m_axi_rlast  (dw_rlast),     .m_axi_rvalid (dw_rvalid),
      |    .m_axi_rready (dw_rready)
      |  );
      |
      |  // ── HBM2 ─────────────────────────────────────────────────────────
      |  // AXI_00: XDMA H2C DMA (via dwidth converter, 256-bit, 33-bit addr)
      |  // AXI_01: SoC mem_axi4 (MC0 port 1) (256-bit, 33-bit addr, len truncated [7:0]→[3:0])
      |  hbm_0 hbm (
      |    .HBM_REF_CLK_0   (hbm_ref_clk),
      |    // AXI_00 — XDMA H2C
      |    .AXI_00_ACLK     (axi_aclk),
      |    .AXI_00_ARESET_N (axi_aresetn),
      |    .AXI_00_AWID     (6'h0),
      |    .AXI_00_AWADDR   ({1'b0, dw_awaddr}),
      |    .AXI_00_AWLEN    (dw_awlen[3:0]),
      |    .AXI_00_AWSIZE   (dw_awsize),
      |    .AXI_00_AWBURST  (dw_awburst),
      |    .AXI_00_AWVALID  (dw_awvalid),
      |    .AXI_00_AWREADY  (dw_awready),
      |    .AXI_00_WDATA    (dw_wdata),
      |    .AXI_00_WSTRB    (dw_wstrb),
      |    .AXI_00_WDATA_PARITY(32'h0),
      |    .AXI_00_WLAST    (dw_wlast),
      |    .AXI_00_WVALID   (dw_wvalid),
      |    .AXI_00_WREADY   (dw_wready),
      |    .AXI_00_BID      (),
      |    .AXI_00_BRESP    (dw_bresp),
      |    .AXI_00_BVALID   (dw_bvalid),
      |    .AXI_00_BREADY   (dw_bready),
      |    .AXI_00_ARID     (6'h0),
      |    .AXI_00_ARADDR   ({1'b0, dw_araddr}),
      |    .AXI_00_ARLEN    (dw_arlen[3:0]),
      |    .AXI_00_ARSIZE   (dw_arsize),
      |    .AXI_00_ARBURST  (dw_arburst),
      |    .AXI_00_ARVALID  (dw_arvalid),
      |    .AXI_00_ARREADY  (dw_arready),
      |    .AXI_00_RID      (),
      |    .AXI_00_RDATA    (dw_rdata),
      |    .AXI_00_RDATA_PARITY(),
      |    .AXI_00_RRESP    (dw_rresp),
      |    .AXI_00_RLAST    (dw_rlast),
      |    .AXI_00_RVALID   (dw_rvalid),
      |    .AXI_00_RREADY   (dw_rready),
      |    // AXI_16 — SoC (MC01, len[7:0]→[3:0])
      |    .AXI_01_ACLK     (axi_aclk),
      |    .AXI_01_ARESET_N (axi_aresetn),
      |    .AXI_01_AWID     (chip_mem_awid),
      |    .AXI_01_AWADDR   (chip_mem_awaddr),
      |    .AXI_01_AWLEN    (chip_mem_awlen[3:0]),
      |    .AXI_01_AWSIZE   (chip_mem_awsize),
      |    .AXI_01_AWBURST  (chip_mem_awburst),
      |    .AXI_01_AWVALID  (chip_mem_awvalid),
      |    .AXI_01_AWREADY  (chip_mem_awready),
      |    .AXI_01_WDATA    (chip_mem_wdata),
      |    .AXI_01_WSTRB    (chip_mem_wstrb),
      |    .AXI_01_WDATA_PARITY(32'h0),
      |    .AXI_01_WLAST    (chip_mem_wlast),
      |    .AXI_01_WVALID   (chip_mem_wvalid),
      |    .AXI_01_WREADY   (chip_mem_wready),
      |    .AXI_01_BID      (chip_mem_bid),
      |    .AXI_01_BRESP    (chip_mem_bresp),
      |    .AXI_01_BVALID   (chip_mem_bvalid),
      |    .AXI_01_BREADY   (chip_mem_bready),
      |    .AXI_01_ARID     (chip_mem_arid),
      |    .AXI_01_ARADDR   (chip_mem_araddr),
      |    .AXI_01_ARLEN    (chip_mem_arlen[3:0]),
      |    .AXI_01_ARSIZE   (chip_mem_arsize),
      |    .AXI_01_ARBURST  (chip_mem_arburst),
      |    .AXI_01_ARVALID  (chip_mem_arvalid),
      |    .AXI_01_ARREADY  (chip_mem_arready),
      |    .AXI_01_RID      (chip_mem_rid),
      |    .AXI_01_RDATA    (chip_mem_rdata),
      |    .AXI_01_RDATA_PARITY(),
      |    .AXI_01_RRESP    (chip_mem_rresp),
      |    .AXI_01_RLAST    (chip_mem_rlast),
      |    .AXI_01_RVALID   (chip_mem_rvalid),
      |    .AXI_01_RREADY   (chip_mem_rready),
      |    // APB init (required)
      |    .APB_0_PCLK    (axi_aclk),
      |    .APB_0_PRESET_N(axi_aresetn),
      |    .APB_0_PADDR   (22'h0),
      |    .APB_0_PWDATA  (32'h0),
      |    .APB_0_PSEL    (1'b0),
      |    .APB_0_PENABLE (1'b0),
      |    .APB_0_PWRITE  (1'b0),
      |    .APB_0_PREADY  (),
      |    .APB_0_PRDATA  (),
      |    .APB_0_PSLVERR (),
      |    .apb_complete_0(),
      |    // Unused AXI ports — tie off all inputs to prevent HBM IP internal
      |    // LUTs from having undriven pins (Vivado Opt 31-67).
      |    // MC_00 exposes AXI_00..07; AXI_00 and AXI_01 are used above.
      |    .AXI_02_ACLK(axi_aclk), .AXI_02_ARESET_N(axi_aresetn),
      |    .AXI_02_AWID(6'h0), .AXI_02_AWADDR(33'h0), .AXI_02_AWLEN(4'h0),
      |    .AXI_02_AWSIZE(3'h0), .AXI_02_AWBURST(2'h0), .AXI_02_AWVALID(1'b0),
      |    .AXI_02_WDATA(256'h0), .AXI_02_WSTRB(32'h0), .AXI_02_WDATA_PARITY(32'h0),
      |    .AXI_02_WLAST(1'b0), .AXI_02_WVALID(1'b0), .AXI_02_BREADY(1'b0),
      |    .AXI_02_ARID(6'h0), .AXI_02_ARADDR(33'h0), .AXI_02_ARLEN(4'h0),
      |    .AXI_02_ARSIZE(3'h0), .AXI_02_ARBURST(2'h0), .AXI_02_ARVALID(1'b0),
      |    .AXI_02_RREADY(1'b0),
      |    .AXI_03_ACLK(axi_aclk), .AXI_03_ARESET_N(axi_aresetn),
      |    .AXI_03_AWID(6'h0), .AXI_03_AWADDR(33'h0), .AXI_03_AWLEN(4'h0),
      |    .AXI_03_AWSIZE(3'h0), .AXI_03_AWBURST(2'h0), .AXI_03_AWVALID(1'b0),
      |    .AXI_03_WDATA(256'h0), .AXI_03_WSTRB(32'h0), .AXI_03_WDATA_PARITY(32'h0),
      |    .AXI_03_WLAST(1'b0), .AXI_03_WVALID(1'b0), .AXI_03_BREADY(1'b0),
      |    .AXI_03_ARID(6'h0), .AXI_03_ARADDR(33'h0), .AXI_03_ARLEN(4'h0),
      |    .AXI_03_ARSIZE(3'h0), .AXI_03_ARBURST(2'h0), .AXI_03_ARVALID(1'b0),
      |    .AXI_03_RREADY(1'b0),
      |    .AXI_04_ACLK(axi_aclk), .AXI_04_ARESET_N(axi_aresetn),
      |    .AXI_04_AWID(6'h0), .AXI_04_AWADDR(33'h0), .AXI_04_AWLEN(4'h0),
      |    .AXI_04_AWSIZE(3'h0), .AXI_04_AWBURST(2'h0), .AXI_04_AWVALID(1'b0),
      |    .AXI_04_WDATA(256'h0), .AXI_04_WSTRB(32'h0), .AXI_04_WDATA_PARITY(32'h0),
      |    .AXI_04_WLAST(1'b0), .AXI_04_WVALID(1'b0), .AXI_04_BREADY(1'b0),
      |    .AXI_04_ARID(6'h0), .AXI_04_ARADDR(33'h0), .AXI_04_ARLEN(4'h0),
      |    .AXI_04_ARSIZE(3'h0), .AXI_04_ARBURST(2'h0), .AXI_04_ARVALID(1'b0),
      |    .AXI_04_RREADY(1'b0),
      |    .AXI_05_ACLK(axi_aclk), .AXI_05_ARESET_N(axi_aresetn),
      |    .AXI_05_AWID(6'h0), .AXI_05_AWADDR(33'h0), .AXI_05_AWLEN(4'h0),
      |    .AXI_05_AWSIZE(3'h0), .AXI_05_AWBURST(2'h0), .AXI_05_AWVALID(1'b0),
      |    .AXI_05_WDATA(256'h0), .AXI_05_WSTRB(32'h0), .AXI_05_WDATA_PARITY(32'h0),
      |    .AXI_05_WLAST(1'b0), .AXI_05_WVALID(1'b0), .AXI_05_BREADY(1'b0),
      |    .AXI_05_ARID(6'h0), .AXI_05_ARADDR(33'h0), .AXI_05_ARLEN(4'h0),
      |    .AXI_05_ARSIZE(3'h0), .AXI_05_ARBURST(2'h0), .AXI_05_ARVALID(1'b0),
      |    .AXI_05_RREADY(1'b0),
      |    .AXI_06_ACLK(axi_aclk), .AXI_06_ARESET_N(axi_aresetn),
      |    .AXI_06_AWID(6'h0), .AXI_06_AWADDR(33'h0), .AXI_06_AWLEN(4'h0),
      |    .AXI_06_AWSIZE(3'h0), .AXI_06_AWBURST(2'h0), .AXI_06_AWVALID(1'b0),
      |    .AXI_06_WDATA(256'h0), .AXI_06_WSTRB(32'h0), .AXI_06_WDATA_PARITY(32'h0),
      |    .AXI_06_WLAST(1'b0), .AXI_06_WVALID(1'b0), .AXI_06_BREADY(1'b0),
      |    .AXI_06_ARID(6'h0), .AXI_06_ARADDR(33'h0), .AXI_06_ARLEN(4'h0),
      |    .AXI_06_ARSIZE(3'h0), .AXI_06_ARBURST(2'h0), .AXI_06_ARVALID(1'b0),
      |    .AXI_06_RREADY(1'b0),
      |    .AXI_07_ACLK(axi_aclk), .AXI_07_ARESET_N(axi_aresetn),
      |    .AXI_07_AWID(6'h0), .AXI_07_AWADDR(33'h0), .AXI_07_AWLEN(4'h0),
      |    .AXI_07_AWSIZE(3'h0), .AXI_07_AWBURST(2'h0), .AXI_07_AWVALID(1'b0),
      |    .AXI_07_WDATA(256'h0), .AXI_07_WSTRB(32'h0), .AXI_07_WDATA_PARITY(32'h0),
      |    .AXI_07_WLAST(1'b0), .AXI_07_WVALID(1'b0), .AXI_07_BREADY(1'b0),
      |    .AXI_07_ARID(6'h0), .AXI_07_ARADDR(33'h0), .AXI_07_ARLEN(4'h0),
      |    .AXI_07_ARSIZE(3'h0), .AXI_07_ARBURST(2'h0), .AXI_07_ARVALID(1'b0),
      |    .AXI_07_RREADY(1'b0),
      |    .AXI_08_ACLK(axi_aclk), .AXI_08_ARESET_N(axi_aresetn),
      |    .AXI_08_AWID(6'h0), .AXI_08_AWADDR(33'h0), .AXI_08_AWLEN(4'h0),
      |    .AXI_08_AWSIZE(3'h0), .AXI_08_AWBURST(2'h0), .AXI_08_AWVALID(1'b0),
      |    .AXI_08_WDATA(256'h0), .AXI_08_WSTRB(32'h0), .AXI_08_WDATA_PARITY(32'h0),
      |    .AXI_08_WLAST(1'b0), .AXI_08_WVALID(1'b0), .AXI_08_BREADY(1'b0),
      |    .AXI_08_ARID(6'h0), .AXI_08_ARADDR(33'h0), .AXI_08_ARLEN(4'h0),
      |    .AXI_08_ARSIZE(3'h0), .AXI_08_ARBURST(2'h0), .AXI_08_ARVALID(1'b0),
      |    .AXI_08_RREADY(1'b0),
      |    .AXI_09_ACLK(axi_aclk), .AXI_09_ARESET_N(axi_aresetn),
      |    .AXI_09_AWID(6'h0), .AXI_09_AWADDR(33'h0), .AXI_09_AWLEN(4'h0),
      |    .AXI_09_AWSIZE(3'h0), .AXI_09_AWBURST(2'h0), .AXI_09_AWVALID(1'b0),
      |    .AXI_09_WDATA(256'h0), .AXI_09_WSTRB(32'h0), .AXI_09_WDATA_PARITY(32'h0),
      |    .AXI_09_WLAST(1'b0), .AXI_09_WVALID(1'b0), .AXI_09_BREADY(1'b0),
      |    .AXI_09_ARID(6'h0), .AXI_09_ARADDR(33'h0), .AXI_09_ARLEN(4'h0),
      |    .AXI_09_ARSIZE(3'h0), .AXI_09_ARBURST(2'h0), .AXI_09_ARVALID(1'b0),
      |    .AXI_09_RREADY(1'b0),
      |    .AXI_10_ACLK(axi_aclk), .AXI_10_ARESET_N(axi_aresetn),
      |    .AXI_10_AWID(6'h0), .AXI_10_AWADDR(33'h0), .AXI_10_AWLEN(4'h0),
      |    .AXI_10_AWSIZE(3'h0), .AXI_10_AWBURST(2'h0), .AXI_10_AWVALID(1'b0),
      |    .AXI_10_WDATA(256'h0), .AXI_10_WSTRB(32'h0), .AXI_10_WDATA_PARITY(32'h0),
      |    .AXI_10_WLAST(1'b0), .AXI_10_WVALID(1'b0), .AXI_10_BREADY(1'b0),
      |    .AXI_10_ARID(6'h0), .AXI_10_ARADDR(33'h0), .AXI_10_ARLEN(4'h0),
      |    .AXI_10_ARSIZE(3'h0), .AXI_10_ARBURST(2'h0), .AXI_10_ARVALID(1'b0),
      |    .AXI_10_RREADY(1'b0),
      |    .AXI_11_ACLK(axi_aclk), .AXI_11_ARESET_N(axi_aresetn),
      |    .AXI_11_AWID(6'h0), .AXI_11_AWADDR(33'h0), .AXI_11_AWLEN(4'h0),
      |    .AXI_11_AWSIZE(3'h0), .AXI_11_AWBURST(2'h0), .AXI_11_AWVALID(1'b0),
      |    .AXI_11_WDATA(256'h0), .AXI_11_WSTRB(32'h0), .AXI_11_WDATA_PARITY(32'h0),
      |    .AXI_11_WLAST(1'b0), .AXI_11_WVALID(1'b0), .AXI_11_BREADY(1'b0),
      |    .AXI_11_ARID(6'h0), .AXI_11_ARADDR(33'h0), .AXI_11_ARLEN(4'h0),
      |    .AXI_11_ARSIZE(3'h0), .AXI_11_ARBURST(2'h0), .AXI_11_ARVALID(1'b0),
      |    .AXI_11_RREADY(1'b0),
      |    .AXI_12_ACLK(axi_aclk), .AXI_12_ARESET_N(axi_aresetn),
      |    .AXI_12_AWID(6'h0), .AXI_12_AWADDR(33'h0), .AXI_12_AWLEN(4'h0),
      |    .AXI_12_AWSIZE(3'h0), .AXI_12_AWBURST(2'h0), .AXI_12_AWVALID(1'b0),
      |    .AXI_12_WDATA(256'h0), .AXI_12_WSTRB(32'h0), .AXI_12_WDATA_PARITY(32'h0),
      |    .AXI_12_WLAST(1'b0), .AXI_12_WVALID(1'b0), .AXI_12_BREADY(1'b0),
      |    .AXI_12_ARID(6'h0), .AXI_12_ARADDR(33'h0), .AXI_12_ARLEN(4'h0),
      |    .AXI_12_ARSIZE(3'h0), .AXI_12_ARBURST(2'h0), .AXI_12_ARVALID(1'b0),
      |    .AXI_12_RREADY(1'b0),
      |    .AXI_13_ACLK(axi_aclk), .AXI_13_ARESET_N(axi_aresetn),
      |    .AXI_13_AWID(6'h0), .AXI_13_AWADDR(33'h0), .AXI_13_AWLEN(4'h0),
      |    .AXI_13_AWSIZE(3'h0), .AXI_13_AWBURST(2'h0), .AXI_13_AWVALID(1'b0),
      |    .AXI_13_WDATA(256'h0), .AXI_13_WSTRB(32'h0), .AXI_13_WDATA_PARITY(32'h0),
      |    .AXI_13_WLAST(1'b0), .AXI_13_WVALID(1'b0), .AXI_13_BREADY(1'b0),
      |    .AXI_13_ARID(6'h0), .AXI_13_ARADDR(33'h0), .AXI_13_ARLEN(4'h0),
      |    .AXI_13_ARSIZE(3'h0), .AXI_13_ARBURST(2'h0), .AXI_13_ARVALID(1'b0),
      |    .AXI_13_RREADY(1'b0),
      |    .AXI_14_ACLK(axi_aclk), .AXI_14_ARESET_N(axi_aresetn),
      |    .AXI_14_AWID(6'h0), .AXI_14_AWADDR(33'h0), .AXI_14_AWLEN(4'h0),
      |    .AXI_14_AWSIZE(3'h0), .AXI_14_AWBURST(2'h0), .AXI_14_AWVALID(1'b0),
      |    .AXI_14_WDATA(256'h0), .AXI_14_WSTRB(32'h0), .AXI_14_WDATA_PARITY(32'h0),
      |    .AXI_14_WLAST(1'b0), .AXI_14_WVALID(1'b0), .AXI_14_BREADY(1'b0),
      |    .AXI_14_ARID(6'h0), .AXI_14_ARADDR(33'h0), .AXI_14_ARLEN(4'h0),
      |    .AXI_14_ARSIZE(3'h0), .AXI_14_ARBURST(2'h0), .AXI_14_ARVALID(1'b0),
      |    .AXI_14_RREADY(1'b0),
      |    .AXI_15_ACLK(axi_aclk), .AXI_15_ARESET_N(axi_aresetn),
      |    .AXI_15_AWID(6'h0), .AXI_15_AWADDR(33'h0), .AXI_15_AWLEN(4'h0),
      |    .AXI_15_AWSIZE(3'h0), .AXI_15_AWBURST(2'h0), .AXI_15_AWVALID(1'b0),
      |    .AXI_15_WDATA(256'h0), .AXI_15_WSTRB(32'h0), .AXI_15_WDATA_PARITY(32'h0),
      |    .AXI_15_WLAST(1'b0), .AXI_15_WVALID(1'b0), .AXI_15_BREADY(1'b0),
      |    .AXI_15_ARID(6'h0), .AXI_15_ARADDR(33'h0), .AXI_15_ARLEN(4'h0),
      |    .AXI_15_ARSIZE(3'h0), .AXI_15_ARBURST(2'h0), .AXI_15_ARVALID(1'b0),
      |    .AXI_15_RREADY(1'b0)
      |  );
      |
      |endmodule
      |""".stripMargin)
}
