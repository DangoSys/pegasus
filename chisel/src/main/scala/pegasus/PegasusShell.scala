package pegasus

import chisel3._
import chisel3.util.HasBlackBoxInline

// PegasusShell: inline Verilog BlackBox wrapping XDMA + DDR4 + SCU.
//
// Architecture:
//   XDMA M_AXI (512-bit, AXI MM DMA)  →  dwidth_h2c (512→64)
//                                       →  axi_clkconv_h2c (axi_aclk→ui_clk)
//                                       →  axi_ic_ddr4 (2→1 mux)  →  ddr4_0
//   chip_mem (SoC, 256-bit)            →  dwidth_soc (256→64)
//                                       →  axi_clkconv_soc (axi_aclk→ui_clk)
//                                       →  axi_ic_ddr4
//   XDMA M_AXI_LITE (BAR1 AXI-Lite)   →  SCU (ctrl[0]=cpu_hold_reset)
//
// SCU register map (BAR1, offset 0):
//   0x000  ctrl[31:0]  bit0=cpu_hold_reset  (reset=1, CPU held until driver writes 0)
//
// Address mapping:
//   DDR4 AXI addr 0x0 = SoC paddr 0x80000000 (DRAM base)
//   H2C pwrite offset = SoC paddr - 0x80000000
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

    // DDR4 physical pins
    val c0_sys_clk_p   = Input(Bool())
    val c0_sys_clk_n   = Input(Bool())
    val c0_ddr4_act_n  = Output(Bool())
    val c0_ddr4_adr    = Output(UInt(17.W))
    val c0_ddr4_ba     = Output(UInt(2.W))
    val c0_ddr4_bg     = Output(UInt(2.W))
    val c0_ddr4_cke    = Output(UInt(1.W))
    val c0_ddr4_odt    = Output(UInt(1.W))
    val c0_ddr4_cs_n   = Output(UInt(1.W))
    val c0_ddr4_ck_t   = Output(UInt(1.W))
    val c0_ddr4_ck_c   = Output(UInt(1.W))
    val c0_ddr4_reset_n = Output(Bool())
    val c0_ddr4_parity = Output(Bool())

    // SoC clock/reset (from XDMA axi_aclk / SCU)
    val dut_clk   = Output(Clock())
    val dut_reset = Output(Bool())   // active-high: ~axi_aresetn | cpu_hold_reset

    // SoC UART
    val uart_tx = Input(Bool())

    // SoC mem_axi4 → DDR4 (64-bit, 32-bit addr, matches DigitalTop mem_axi4_0)
    val chip_mem_awid    = Input(UInt(4.W))
    val chip_mem_awaddr  = Input(UInt(32.W))
    val chip_mem_awlen   = Input(UInt(8.W))
    val chip_mem_awsize  = Input(UInt(3.W))
    val chip_mem_awburst = Input(UInt(2.W))
    val chip_mem_awvalid = Input(Bool())
    val chip_mem_awready = Output(Bool())

    val chip_mem_wdata  = Input(UInt(64.W))
    val chip_mem_wstrb  = Input(UInt(8.W))
    val chip_mem_wlast  = Input(Bool())
    val chip_mem_wvalid = Input(Bool())
    val chip_mem_wready = Output(Bool())

    val chip_mem_bid    = Output(UInt(4.W))
    val chip_mem_bresp  = Output(UInt(2.W))
    val chip_mem_bvalid = Output(Bool())
    val chip_mem_bready = Input(Bool())

    val chip_mem_arid    = Input(UInt(4.W))
    val chip_mem_araddr  = Input(UInt(32.W))
    val chip_mem_arlen   = Input(UInt(8.W))
    val chip_mem_arsize  = Input(UInt(3.W))
    val chip_mem_arburst = Input(UInt(2.W))
    val chip_mem_arvalid = Input(Bool())
    val chip_mem_arready = Output(Bool())

    val chip_mem_rid    = Output(UInt(4.W))
    val chip_mem_rdata  = Output(UInt(64.W))
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
      |  input         c0_sys_clk_p,
      |  input         c0_sys_clk_n,
      |  output        c0_ddr4_act_n,
      |  output [16:0] c0_ddr4_adr,
      |  output [1:0]  c0_ddr4_ba,
      |  output [1:0]  c0_ddr4_bg,
      |  output [0:0]  c0_ddr4_cke,
      |  output [0:0]  c0_ddr4_odt,
      |  output [0:0]  c0_ddr4_cs_n,
      |  output [0:0]  c0_ddr4_ck_t,
      |  output [0:0]  c0_ddr4_ck_c,
      |  output        c0_ddr4_reset_n,
      |  output        c0_ddr4_parity,
      |  inout  [71:0] c0_ddr4_dq,
      |  inout  [17:0] c0_ddr4_dqs_c,
      |  inout  [17:0] c0_ddr4_dqs_t,
      |  output        dut_clk,
      |  output        dut_reset,
      |  input         uart_tx,
      |  input  [3:0]   chip_mem_awid,
      |  input  [31:0]  chip_mem_awaddr,
      |  input  [7:0]   chip_mem_awlen,
      |  input  [2:0]   chip_mem_awsize,
      |  input  [1:0]   chip_mem_awburst,
      |  input          chip_mem_awvalid,
      |  output         chip_mem_awready,
      |  input  [63:0]  chip_mem_wdata,
      |  input  [7:0]   chip_mem_wstrb,
      |  input          chip_mem_wlast,
      |  input          chip_mem_wvalid,
      |  output         chip_mem_wready,
      |  output [3:0]   chip_mem_bid,
      |  output [1:0]   chip_mem_bresp,
      |  output         chip_mem_bvalid,
      |  input          chip_mem_bready,
      |  input  [3:0]   chip_mem_arid,
      |  input  [31:0]  chip_mem_araddr,
      |  input  [7:0]   chip_mem_arlen,
      |  input  [2:0]   chip_mem_arsize,
      |  input  [1:0]   chip_mem_arburst,
      |  input          chip_mem_arvalid,
      |  output         chip_mem_arready,
      |  output [3:0]   chip_mem_rid,
      |  output [63:0]  chip_mem_rdata,
      |  output [1:0]   chip_mem_rresp,
      |  output         chip_mem_rlast,
      |  output         chip_mem_rvalid,
      |  input          chip_mem_rready
      |);
      |
      |  wire axi_aclk;
      |  wire axi_aresetn;
      |  wire pcie_sys_clk_gt_buf;
      |  wire ddr4_ui_clk;
      |  wire ddr4_ui_clk_sync_rst;
      |  wire ddr4_aresetn;
      |  assign ddr4_aresetn = ~ddr4_ui_clk_sync_rst;
      |
      |  // ── XDMA M_AXI wires (512-bit, AXI MM DMA) ───────────────────────
      |  wire [3:0]   xdma_awid;
      |  wire [33:0]  xdma_awaddr;
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
      |  wire [33:0]  xdma_araddr;
      |  wire [7:0]   xdma_arlen;
      |  wire [2:0]   xdma_arsize;
      |  wire [1:0]   xdma_arburst;
      |  wire         xdma_arvalid, xdma_arready;
      |  wire [3:0]   xdma_rid;
      |  wire [511:0] xdma_rdata;
      |  wire [1:0]   xdma_rresp;
      |  wire         xdma_rlast, xdma_rvalid, xdma_rready;
      |
      |  // ── XDMA M_AXI_LITE wires (SCU) ──────────────────────────────────
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
      |  // ── dwidth_h2c master (64-bit) ────────────────────────────────────
      |  wire [33:0]  dh_awaddr;
      |  wire [7:0]   dh_awlen;
      |  wire [2:0]   dh_awsize;
      |  wire [1:0]   dh_awburst;
      |  wire         dh_awvalid, dh_awready;
      |  wire [63:0]  dh_wdata;
      |  wire [7:0]   dh_wstrb;
      |  wire         dh_wlast, dh_wvalid, dh_wready;
      |  wire [1:0]   dh_bresp;
      |  wire         dh_bvalid, dh_bready;
      |  wire [33:0]  dh_araddr;
      |  wire [7:0]   dh_arlen;
      |  wire [2:0]   dh_arsize;
      |  wire [1:0]   dh_arburst;
      |  wire         dh_arvalid, dh_arready;
      |  wire [63:0]  dh_rdata;
      |  wire [1:0]   dh_rresp;
      |  wire         dh_rlast, dh_rvalid, dh_rready;
      |
      |  // ── dwidth_soc master (64-bit) ────────────────────────────────────
      |  wire [33:0]  ds_awaddr;
      |  wire [7:0]   ds_awlen;
      |  wire [2:0]   ds_awsize;
      |  wire [1:0]   ds_awburst;
      |  wire         ds_awvalid, ds_awready;
      |  wire [63:0]  ds_wdata;
      |  wire [7:0]   ds_wstrb;
      |  wire         ds_wlast, ds_wvalid, ds_wready;
      |  wire [5:0]   ds_bid;
      |  wire [1:0]   ds_bresp;
      |  wire         ds_bvalid, ds_bready;
      |  wire [33:0]  ds_araddr;
      |  wire [7:0]   ds_arlen;
      |  wire [2:0]   ds_arsize;
      |  wire [1:0]   ds_arburst;
      |  wire         ds_arvalid, ds_arready;
      |  wire [5:0]   ds_rid;
      |  wire [63:0]  ds_rdata;
      |  wire [1:0]   ds_rresp;
      |  wire         ds_rlast, ds_rvalid, ds_rready;
      |
      |  // ── axi_clkconv_h2c master (ui_clk domain, 64-bit) ───────────────
      |  wire [33:0]  ch_awaddr; wire [7:0] ch_awlen; wire [2:0] ch_awsize;
      |  wire [1:0]   ch_awburst; wire ch_awvalid, ch_awready;
      |  wire [63:0]  ch_wdata; wire [7:0] ch_wstrb;
      |  wire         ch_wlast, ch_wvalid, ch_wready;
      |  wire [1:0]   ch_bresp; wire ch_bvalid, ch_bready;
      |  wire [33:0]  ch_araddr; wire [7:0] ch_arlen; wire [2:0] ch_arsize;
      |  wire [1:0]   ch_arburst; wire ch_arvalid, ch_arready;
      |  wire [63:0]  ch_rdata; wire [1:0] ch_rresp;
      |  wire         ch_rlast, ch_rvalid, ch_rready;
      |
      |  // ── axi_clkconv_soc master (ui_clk domain, 64-bit) ───────────────
      |  wire [33:0]  cs_awaddr; wire [7:0] cs_awlen; wire [2:0] cs_awsize;
      |  wire [1:0]   cs_awburst; wire cs_awvalid, cs_awready;
      |  wire [63:0]  cs_wdata; wire [7:0] cs_wstrb;
      |  wire         cs_wlast, cs_wvalid, cs_wready;
      |  wire [5:0]   cs_bid; wire [1:0] cs_bresp; wire cs_bvalid, cs_bready;
      |  wire [33:0]  cs_araddr; wire [7:0] cs_arlen; wire [2:0] cs_arsize;
      |  wire [1:0]   cs_arburst; wire cs_arvalid, cs_arready;
      |  wire [5:0]   cs_rid; wire [63:0] cs_rdata; wire [1:0] cs_rresp;
      |  wire         cs_rlast, cs_rvalid, cs_rready;
      |
      |  // ── ic master → DDR4 (64-bit, ui_clk) ───────────────────────────
      |  wire [33:0]  ic_awaddr; wire [7:0] ic_awlen; wire [2:0] ic_awsize;
      |  wire [1:0]   ic_awburst; wire ic_awvalid, ic_awready;
      |  wire [63:0]  ic_wdata; wire [7:0] ic_wstrb;
      |  wire         ic_wlast, ic_wvalid, ic_wready;
      |  wire [1:0]   ic_bresp; wire ic_bvalid, ic_bready;
      |  wire [33:0]  ic_araddr; wire [7:0] ic_arlen; wire [2:0] ic_arsize;
      |  wire [1:0]   ic_arburst; wire ic_arvalid, ic_arready;
      |  wire [63:0]  ic_rdata; wire [1:0] ic_rresp;
      |  wire         ic_rlast, ic_rvalid, ic_rready;
      |
      |  wire cpu_hold_reset;
      |
      |  // ── IBUFDS_GTE4 ──────────────────────────────────────────────────
      |  IBUFDS_GTE4 #(
      |    .REFCLK_EN_TX_PATH(1'b0), .REFCLK_HROW_CK_SEL(2'b00), .REFCLK_ICNTL_RX(2'b00)
      |  ) ibufds_gt_clk (
      |    .I(pcie_sys_clk_gt), .IB(1'b0), .CEB(1'b0), .O(pcie_sys_clk_gt_buf), .ODIV2()
      |  );
      |
      |  // ── XDMA (AXI Memory Mapped DMA mode) ────────────────────────────
      |  xdma_0 xdma (
      |    .sys_clk(pcie_sys_clk), .sys_clk_gt(pcie_sys_clk_gt_buf),
      |    .sys_rst_n(pcie_sys_rst_n),
      |    .pci_exp_txp(pcie_exp_txp), .pci_exp_txn(pcie_exp_txn),
      |    .pci_exp_rxp(pcie_exp_rxp), .pci_exp_rxn(pcie_exp_rxn),
      |    .axi_aclk(axi_aclk), .axi_aresetn(axi_aresetn),
      |    .usr_irq_req(4'h0),
      |    // M_AXI — DMA host-to-card / card-to-host
      |    .m_axi_awid(xdma_awid),       .m_axi_awaddr(xdma_awaddr),
      |    .m_axi_awlen(xdma_awlen),     .m_axi_awsize(xdma_awsize),
      |    .m_axi_awburst(xdma_awburst), .m_axi_awprot(3'h0),
      |    .m_axi_awvalid(xdma_awvalid), .m_axi_awready(xdma_awready),
      |    .m_axi_awlock(1'b0),          .m_axi_awcache(4'h0),
      |    .m_axi_wdata(xdma_wdata),     .m_axi_wstrb(xdma_wstrb),
      |    .m_axi_wlast(xdma_wlast),     .m_axi_wvalid(xdma_wvalid),
      |    .m_axi_wready(xdma_wready),
      |    .m_axi_bid(xdma_bid),         .m_axi_bresp(xdma_bresp),
      |    .m_axi_bvalid(xdma_bvalid),   .m_axi_bready(xdma_bready),
      |    .m_axi_arid(xdma_arid),       .m_axi_araddr(xdma_araddr),
      |    .m_axi_arlen(xdma_arlen),     .m_axi_arsize(xdma_arsize),
      |    .m_axi_arburst(xdma_arburst), .m_axi_arprot(3'h0),
      |    .m_axi_arvalid(xdma_arvalid), .m_axi_arready(xdma_arready),
      |    .m_axi_arlock(1'b0),          .m_axi_arcache(4'h0),
      |    .m_axi_rid(xdma_rid),         .m_axi_rdata(xdma_rdata),
      |    .m_axi_rresp(xdma_rresp),     .m_axi_rlast(xdma_rlast),
      |    .m_axi_rvalid(xdma_rvalid),   .m_axi_rready(xdma_rready),
      |    // M_AXI_LITE — SCU
      |    .m_axil_awaddr(axil_awaddr),   .m_axil_awprot(axil_awprot),
      |    .m_axil_awvalid(axil_awvalid), .m_axil_awready(axil_awready),
      |    .m_axil_wdata(axil_wdata),     .m_axil_wstrb(axil_wstrb),
      |    .m_axil_wvalid(axil_wvalid),   .m_axil_wready(axil_wready),
      |    .m_axil_bvalid(axil_bvalid),   .m_axil_bresp(axil_bresp),
      |    .m_axil_bready(axil_bready),
      |    .m_axil_araddr(axil_araddr),   .m_axil_arprot(axil_arprot),
      |    .m_axil_arvalid(axil_arvalid), .m_axil_arready(axil_arready),
      |    .m_axil_rdata(axil_rdata),     .m_axil_rresp(axil_rresp),
      |    .m_axil_rvalid(axil_rvalid),   .m_axil_rready(axil_rready)
      |  );
      |
      |  assign dut_clk   = axi_aclk;
      |  assign dut_reset = ~axi_aresetn | cpu_hold_reset;
      |
      |  // ── SCU — AXI-Lite slave ──────────────────────────────────────────
      |  reg [31:0] scu_ctrl;
      |  reg        scu_aw_pend, scu_bvalid_r, scu_rvalid_r;
      |  reg [31:0] scu_rdata_r;
      |
      |  always @(posedge axi_aclk or negedge axi_aresetn) begin
      |    if (!axi_aresetn) begin
      |      scu_ctrl     <= 32'h1;
      |      scu_aw_pend  <= 1'b0;
      |      scu_bvalid_r <= 1'b0;
      |      scu_rvalid_r <= 1'b0;
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
      |  // ── H2C: XDMA 512-bit → 64-bit ───────────────────────────────────
      |  axi_dwidth_converter_h2c dwidth_h2c (
      |    .s_axi_aclk(axi_aclk), .s_axi_aresetn(axi_aresetn),
      |    .s_axi_awid(xdma_awid),       .s_axi_awaddr(xdma_awaddr),
      |    .s_axi_awlen(xdma_awlen),     .s_axi_awsize(xdma_awsize),
      |    .s_axi_awburst(xdma_awburst), .s_axi_awlock(1'b0),
      |    .s_axi_awcache(4'h0),         .s_axi_awprot(3'h0),
      |    .s_axi_awregion(4'h0),        .s_axi_awqos(4'h0),
      |    .s_axi_awvalid(xdma_awvalid), .s_axi_awready(xdma_awready),
      |    .s_axi_wdata(xdma_wdata),     .s_axi_wstrb(xdma_wstrb),
      |    .s_axi_wlast(xdma_wlast),     .s_axi_wvalid(xdma_wvalid),
      |    .s_axi_wready(xdma_wready),
      |    .s_axi_bid(xdma_bid),         .s_axi_bresp(xdma_bresp),
      |    .s_axi_bvalid(xdma_bvalid),   .s_axi_bready(xdma_bready),
      |    .s_axi_arid(xdma_arid),       .s_axi_araddr(xdma_araddr),
      |    .s_axi_arlen(xdma_arlen),     .s_axi_arsize(xdma_arsize),
      |    .s_axi_arburst(xdma_arburst), .s_axi_arlock(1'b0),
      |    .s_axi_arcache(4'h0),         .s_axi_arprot(3'h0),
      |    .s_axi_arregion(4'h0),        .s_axi_arqos(4'h0),
      |    .s_axi_arvalid(xdma_arvalid), .s_axi_arready(xdma_arready),
      |    .s_axi_rid(xdma_rid),         .s_axi_rdata(xdma_rdata),
      |    .s_axi_rresp(xdma_rresp),     .s_axi_rlast(xdma_rlast),
      |    .s_axi_rvalid(xdma_rvalid),   .s_axi_rready(xdma_rready),
      |    .m_axi_awaddr(dh_awaddr),     .m_axi_awlen(dh_awlen),
      |    .m_axi_awsize(dh_awsize),     .m_axi_awburst(dh_awburst),
      |    .m_axi_awlock(), .m_axi_awcache(), .m_axi_awprot(),
      |    .m_axi_awregion(), .m_axi_awqos(),
      |    .m_axi_awvalid(dh_awvalid),   .m_axi_awready(dh_awready),
      |    .m_axi_wdata(dh_wdata),       .m_axi_wstrb(dh_wstrb),
      |    .m_axi_wlast(dh_wlast),       .m_axi_wvalid(dh_wvalid),
      |    .m_axi_wready(dh_wready),
      |    .m_axi_bresp(dh_bresp),       .m_axi_bvalid(dh_bvalid),
      |    .m_axi_bready(dh_bready),
      |    .m_axi_araddr(dh_araddr),     .m_axi_arlen(dh_arlen),
      |    .m_axi_arsize(dh_arsize),     .m_axi_arburst(dh_arburst),
      |    .m_axi_arlock(), .m_axi_arcache(), .m_axi_arprot(),
      |    .m_axi_arregion(), .m_axi_arqos(),
      |    .m_axi_arvalid(dh_arvalid),   .m_axi_arready(dh_arready),
      |    .m_axi_rdata(dh_rdata),       .m_axi_rresp(dh_rresp),
      |    .m_axi_rlast(dh_rlast),       .m_axi_rvalid(dh_rvalid),
      |    .m_axi_rready(dh_rready)
      |  );
      |
      |  // ── SoC: chip_mem 64-bit → axi_clkconv_soc (direct, no dwidth) ──
      |  assign ds_awaddr  = {2'b0, chip_mem_awaddr};
      |  assign ds_awlen   = chip_mem_awlen;
      |  assign ds_awsize  = chip_mem_awsize;
      |  assign ds_awburst = chip_mem_awburst;
      |  assign ds_awvalid = chip_mem_awvalid;
      |  assign chip_mem_awready = ds_awready;
      |  assign ds_wdata   = chip_mem_wdata;
      |  assign ds_wstrb   = chip_mem_wstrb;
      |  assign ds_wlast   = chip_mem_wlast;
      |  assign ds_wvalid  = chip_mem_wvalid;
      |  assign chip_mem_wready = ds_wready;
      |  assign chip_mem_bid    = ds_bid[3:0];
      |  assign chip_mem_bresp  = ds_bresp;
      |  assign chip_mem_bvalid = ds_bvalid;
      |  assign ds_bready  = chip_mem_bready;
      |  assign ds_araddr  = {2'b0, chip_mem_araddr};
      |  assign ds_arlen   = chip_mem_arlen;
      |  assign ds_arsize  = chip_mem_arsize;
      |  assign ds_arburst = chip_mem_arburst;
      |  assign ds_arvalid = chip_mem_arvalid;
      |  assign chip_mem_arready = ds_arready;
      |  assign chip_mem_rid    = ds_rid[3:0];
      |  assign chip_mem_rdata  = ds_rdata;
      |  assign chip_mem_rresp  = ds_rresp;
      |  assign chip_mem_rlast  = ds_rlast;
      |  assign chip_mem_rvalid = ds_rvalid;
      |  assign ds_rready  = chip_mem_rready;
      |
      |  // ── Clock converters: axi_aclk → ddr4_ui_clk ─────────────────────
      |  axi_clkconv_h2c clkconv_h2c (
      |    .s_axi_aclk(axi_aclk), .s_axi_aresetn(axi_aresetn),
      |    .m_axi_aclk(ddr4_ui_clk), .m_axi_aresetn(ddr4_aresetn),
      |    .s_axi_awaddr(dh_awaddr), .s_axi_awlen(dh_awlen), .s_axi_awsize(dh_awsize),
      |    .s_axi_awburst(dh_awburst), .s_axi_awlock(1'b0), .s_axi_awcache(4'h0),
      |    .s_axi_awprot(3'h0), .s_axi_awqos(4'h0), .s_axi_awregion(4'h0),
      |    .s_axi_awvalid(dh_awvalid), .s_axi_awready(dh_awready),
      |    .s_axi_wdata(dh_wdata), .s_axi_wstrb(dh_wstrb),
      |    .s_axi_wlast(dh_wlast), .s_axi_wvalid(dh_wvalid), .s_axi_wready(dh_wready),
      |    .s_axi_bresp(dh_bresp), .s_axi_bvalid(dh_bvalid), .s_axi_bready(dh_bready),
      |    .s_axi_araddr(dh_araddr), .s_axi_arlen(dh_arlen), .s_axi_arsize(dh_arsize),
      |    .s_axi_arburst(dh_arburst), .s_axi_arlock(1'b0), .s_axi_arcache(4'h0),
      |    .s_axi_arprot(3'h0), .s_axi_arqos(4'h0), .s_axi_arregion(4'h0),
      |    .s_axi_arvalid(dh_arvalid), .s_axi_arready(dh_arready),
      |    .s_axi_rdata(dh_rdata), .s_axi_rresp(dh_rresp),
      |    .s_axi_rlast(dh_rlast), .s_axi_rvalid(dh_rvalid), .s_axi_rready(dh_rready),
      |    .m_axi_awaddr(ch_awaddr), .m_axi_awlen(ch_awlen), .m_axi_awsize(ch_awsize),
      |    .m_axi_awburst(ch_awburst), .m_axi_awlock(), .m_axi_awcache(), .m_axi_awprot(),
      |    .m_axi_awqos(), .m_axi_awregion(),
      |    .m_axi_awvalid(ch_awvalid), .m_axi_awready(ch_awready),
      |    .m_axi_wdata(ch_wdata), .m_axi_wstrb(ch_wstrb),
      |    .m_axi_wlast(ch_wlast), .m_axi_wvalid(ch_wvalid), .m_axi_wready(ch_wready),
      |    .m_axi_bresp(ch_bresp), .m_axi_bvalid(ch_bvalid), .m_axi_bready(ch_bready),
      |    .m_axi_araddr(ch_araddr), .m_axi_arlen(ch_arlen), .m_axi_arsize(ch_arsize),
      |    .m_axi_arburst(ch_arburst), .m_axi_arlock(), .m_axi_arcache(), .m_axi_arprot(),
      |    .m_axi_arqos(), .m_axi_arregion(),
      |    .m_axi_arvalid(ch_arvalid), .m_axi_arready(ch_arready),
      |    .m_axi_rdata(ch_rdata), .m_axi_rresp(ch_rresp),
      |    .m_axi_rlast(ch_rlast), .m_axi_rvalid(ch_rvalid), .m_axi_rready(ch_rready)
      |  );
      |
      |  axi_clkconv_soc clkconv_soc (
      |    .s_axi_aclk(axi_aclk), .s_axi_aresetn(axi_aresetn),
      |    .m_axi_aclk(ddr4_ui_clk), .m_axi_aresetn(ddr4_aresetn),
      |    .s_axi_awaddr(ds_awaddr), .s_axi_awlen(ds_awlen), .s_axi_awsize(ds_awsize),
      |    .s_axi_awburst(ds_awburst), .s_axi_awlock(1'b0), .s_axi_awcache(4'h0),
      |    .s_axi_awprot(3'h0), .s_axi_awqos(4'h0), .s_axi_awregion(4'h0),
      |    .s_axi_awvalid(ds_awvalid), .s_axi_awready(ds_awready),
      |    .s_axi_wdata(ds_wdata), .s_axi_wstrb(ds_wstrb),
      |    .s_axi_wlast(ds_wlast), .s_axi_wvalid(ds_wvalid), .s_axi_wready(ds_wready),
      |    .s_axi_bid(ds_bid), .s_axi_bresp(ds_bresp),
      |    .s_axi_bvalid(ds_bvalid), .s_axi_bready(ds_bready),
      |    .s_axi_araddr(ds_araddr), .s_axi_arlen(ds_arlen), .s_axi_arsize(ds_arsize),
      |    .s_axi_arburst(ds_arburst), .s_axi_arlock(1'b0), .s_axi_arcache(4'h0),
      |    .s_axi_arprot(3'h0), .s_axi_arqos(4'h0), .s_axi_arregion(4'h0),
      |    .s_axi_arvalid(ds_arvalid), .s_axi_arready(ds_arready),
      |    .s_axi_rid(ds_rid), .s_axi_rdata(ds_rdata), .s_axi_rresp(ds_rresp),
      |    .s_axi_rlast(ds_rlast), .s_axi_rvalid(ds_rvalid), .s_axi_rready(ds_rready),
      |    .m_axi_awaddr(cs_awaddr), .m_axi_awlen(cs_awlen), .m_axi_awsize(cs_awsize),
      |    .m_axi_awburst(cs_awburst), .m_axi_awlock(), .m_axi_awcache(), .m_axi_awprot(),
      |    .m_axi_awqos(), .m_axi_awregion(),
      |    .m_axi_awvalid(cs_awvalid), .m_axi_awready(cs_awready),
      |    .m_axi_wdata(cs_wdata), .m_axi_wstrb(cs_wstrb),
      |    .m_axi_wlast(cs_wlast), .m_axi_wvalid(cs_wvalid), .m_axi_wready(cs_wready),
      |    .m_axi_bid(cs_bid), .m_axi_bresp(cs_bresp),
      |    .m_axi_bvalid(cs_bvalid), .m_axi_bready(cs_bready),
      |    .m_axi_araddr(cs_araddr), .m_axi_arlen(cs_arlen), .m_axi_arsize(cs_arsize),
      |    .m_axi_arburst(cs_arburst), .m_axi_arlock(), .m_axi_arcache(), .m_axi_arprot(),
      |    .m_axi_arqos(), .m_axi_arregion(),
      |    .m_axi_arvalid(cs_arvalid), .m_axi_arready(cs_arready),
      |    .m_axi_rid(cs_rid), .m_axi_rdata(cs_rdata), .m_axi_rresp(cs_rresp),
      |    .m_axi_rlast(cs_rlast), .m_axi_rvalid(cs_rvalid), .m_axi_rready(cs_rready)
      |  );
      |
      |  // ── AXI Crossbar 2→1 (ui_clk domain) ────────────────────────────
      |  // axi_crossbar v2.1: vector ports, NUM_SI=2, NUM_MI=1, 64-bit/34-bit
      |  // s_axi_*: [2*W-1:0] vectors, [1] = SoC (S01), [0] = H2C (S00)
      |  // m_axi_*: scalar (1 master)
      |  axi_ic_ddr4 axi_ic (
      |    .aclk(ddr4_ui_clk), .aresetn(ddr4_aresetn),
      |    .s_axi_awid    ({4'h0,       4'h0}),
      |    .s_axi_awaddr  ({cs_awaddr,  ch_awaddr}),
      |    .s_axi_awlen   ({cs_awlen,   ch_awlen}),
      |    .s_axi_awsize  ({cs_awsize,  ch_awsize}),
      |    .s_axi_awburst ({cs_awburst, ch_awburst}),
      |    .s_axi_awlock  (2'b0),
      |    .s_axi_awcache (8'h0),
      |    .s_axi_awprot  (6'h0),
      |    .s_axi_awqos   (8'h0),
      |    .s_axi_awvalid ({cs_awvalid, ch_awvalid}),
      |    .s_axi_awready ({cs_awready, ch_awready}),
      |    .s_axi_wdata   ({cs_wdata,   ch_wdata}),
      |    .s_axi_wstrb   ({cs_wstrb,   ch_wstrb}),
      |    .s_axi_wlast   ({cs_wlast,   ch_wlast}),
      |    .s_axi_wvalid  ({cs_wvalid,  ch_wvalid}),
      |    .s_axi_wready  ({cs_wready,  ch_wready}),
      |    .s_axi_bid     (),
      |    .s_axi_bresp   ({cs_bresp,   ch_bresp}),
      |    .s_axi_bvalid  ({cs_bvalid,  ch_bvalid}),
      |    .s_axi_bready  ({cs_bready,  ch_bready}),
      |    .s_axi_arid    (8'h0),
      |    .s_axi_araddr  ({cs_araddr,  ch_araddr}),
      |    .s_axi_arlen   ({cs_arlen,   ch_arlen}),
      |    .s_axi_arsize  ({cs_arsize,  ch_arsize}),
      |    .s_axi_arburst ({cs_arburst, ch_arburst}),
      |    .s_axi_arlock  (2'b0),
      |    .s_axi_arcache (8'h0),
      |    .s_axi_arprot  (6'h0),
      |    .s_axi_arqos   (8'h0),
      |    .s_axi_arvalid ({cs_arvalid, ch_arvalid}),
      |    .s_axi_arready ({cs_arready, ch_arready}),
      |    .s_axi_rid     (),
      |    .s_axi_rdata   ({cs_rdata,   ch_rdata}),
      |    .s_axi_rresp   ({cs_rresp,   ch_rresp}),
      |    .s_axi_rlast   ({cs_rlast,   ch_rlast}),
      |    .s_axi_rvalid  ({cs_rvalid,  ch_rvalid}),
      |    .s_axi_rready  ({cs_rready,  ch_rready}),
      |    .m_axi_awid    (),
      |    .m_axi_awaddr  (ic_awaddr),
      |    .m_axi_awlen   (ic_awlen),
      |    .m_axi_awsize  (ic_awsize),
      |    .m_axi_awburst (ic_awburst),
      |    .m_axi_awlock  (), .m_axi_awcache(), .m_axi_awprot(), .m_axi_awqos(),
      |    .m_axi_awvalid (ic_awvalid), .m_axi_awready(ic_awready),
      |    .m_axi_wdata   (ic_wdata),  .m_axi_wstrb(ic_wstrb),
      |    .m_axi_wlast   (ic_wlast),  .m_axi_wvalid(ic_wvalid), .m_axi_wready(ic_wready),
      |    .m_axi_bid     (4'h0),
      |    .m_axi_bresp   (ic_bresp),  .m_axi_bvalid(ic_bvalid), .m_axi_bready(ic_bready),
      |    .m_axi_arid    (),
      |    .m_axi_araddr  (ic_araddr), .m_axi_arlen(ic_arlen),
      |    .m_axi_arsize  (ic_arsize), .m_axi_arburst(ic_arburst),
      |    .m_axi_arlock  (), .m_axi_arcache(), .m_axi_arprot(), .m_axi_arqos(),
      |    .m_axi_arvalid (ic_arvalid), .m_axi_arready(ic_arready),
      |    .m_axi_rid     (4'h0),
      |    .m_axi_rdata   (ic_rdata),  .m_axi_rresp(ic_rresp),
      |    .m_axi_rlast   (ic_rlast),  .m_axi_rvalid(ic_rvalid), .m_axi_rready(ic_rready)
      |  );
      |
      |  // ── DDR4 ─────────────────────────────────────────────────────────
      |  ddr4_0 ddr4 (
      |    .sys_rst(~pcie_sys_rst_n),
      |    .c0_sys_clk_p(c0_sys_clk_p),
      |    .c0_sys_clk_n(c0_sys_clk_n),
      |    .c0_ddr4_act_n(c0_ddr4_act_n),
      |    .c0_ddr4_adr(c0_ddr4_adr),
      |    .c0_ddr4_ba(c0_ddr4_ba),
      |    .c0_ddr4_bg(c0_ddr4_bg),
      |    .c0_ddr4_cke(c0_ddr4_cke),
      |    .c0_ddr4_odt(c0_ddr4_odt),
      |    .c0_ddr4_cs_n(c0_ddr4_cs_n),
      |    .c0_ddr4_ck_t(c0_ddr4_ck_t),
      |    .c0_ddr4_ck_c(c0_ddr4_ck_c),
      |    .c0_ddr4_reset_n(c0_ddr4_reset_n),
      |    .c0_ddr4_parity(c0_ddr4_parity),
      |    .c0_ddr4_dq(c0_ddr4_dq),
      |    .c0_ddr4_dqs_c(c0_ddr4_dqs_c),
      |    .c0_ddr4_dqs_t(c0_ddr4_dqs_t),
      |    .c0_ddr4_ui_clk(ddr4_ui_clk),
      |    .c0_ddr4_ui_clk_sync_rst(ddr4_ui_clk_sync_rst),
      |    .c0_ddr4_aresetn(ddr4_aresetn),
      |    // AXI control slave (unused, tie-off)
      |    .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
      |    .c0_ddr4_s_axi_ctrl_awaddr(32'h0),
      |    .c0_ddr4_s_axi_ctrl_wvalid(1'b0),
      |    .c0_ddr4_s_axi_ctrl_wdata(32'h0),
      |    .c0_ddr4_s_axi_ctrl_bready(1'b1),
      |    .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
      |    .c0_ddr4_s_axi_ctrl_araddr(32'h0),
      |    .c0_ddr4_s_axi_ctrl_rready(1'b1),
      |    .c0_ddr4_interrupt(),
      |    // AXI slave
      |    .c0_ddr4_s_axi_awid(4'h0),
      |    .c0_ddr4_s_axi_awaddr(ic_awaddr),
      |    .c0_ddr4_s_axi_awlen(ic_awlen),
      |    .c0_ddr4_s_axi_awsize(ic_awsize),
      |    .c0_ddr4_s_axi_awburst(ic_awburst),
      |    .c0_ddr4_s_axi_awlock(1'b0),
      |    .c0_ddr4_s_axi_awcache(4'h0),
      |    .c0_ddr4_s_axi_awprot(3'h0),
      |    .c0_ddr4_s_axi_awqos(4'h0),
      |    .c0_ddr4_s_axi_awvalid(ic_awvalid),
      |    .c0_ddr4_s_axi_awready(ic_awready),
      |    .c0_ddr4_s_axi_wdata(ic_wdata),
      |    .c0_ddr4_s_axi_wstrb(ic_wstrb),
      |    .c0_ddr4_s_axi_wlast(ic_wlast),
      |    .c0_ddr4_s_axi_wvalid(ic_wvalid),
      |    .c0_ddr4_s_axi_wready(ic_wready),
      |    .c0_ddr4_s_axi_bid(),
      |    .c0_ddr4_s_axi_bresp(ic_bresp),
      |    .c0_ddr4_s_axi_bvalid(ic_bvalid),
      |    .c0_ddr4_s_axi_bready(ic_bready),
      |    .c0_ddr4_s_axi_arid(4'h0),
      |    .c0_ddr4_s_axi_araddr(ic_araddr),
      |    .c0_ddr4_s_axi_arlen(ic_arlen),
      |    .c0_ddr4_s_axi_arsize(ic_arsize),
      |    .c0_ddr4_s_axi_arburst(ic_arburst),
      |    .c0_ddr4_s_axi_arlock(1'b0),
      |    .c0_ddr4_s_axi_arcache(4'h0),
      |    .c0_ddr4_s_axi_arprot(3'h0),
      |    .c0_ddr4_s_axi_arqos(4'h0),
      |    .c0_ddr4_s_axi_arvalid(ic_arvalid),
      |    .c0_ddr4_s_axi_arready(ic_arready),
      |    .c0_ddr4_s_axi_rid(),
      |    .c0_ddr4_s_axi_rdata(ic_rdata),
      |    .c0_ddr4_s_axi_rresp(ic_rresp),
      |    .c0_ddr4_s_axi_rlast(ic_rlast),
      |    .c0_ddr4_s_axi_rvalid(ic_rvalid),
      |    .c0_ddr4_s_axi_rready(ic_rready),
      |    .c0_init_calib_complete(),
      |    .dbg_clk(),
      |    .dbg_bus()
      |  );
      |
      |endmodule
      |""".stripMargin)
}
