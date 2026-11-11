package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.CashFlow
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Segmented
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val IN_CATS = listOf("入金", "薪資", "利息", "獎金", "其他收入")
private val OUT_CATS = listOf("出金", "生活支出", "房租", "保險", "其他支出")

private fun today() = LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))

/** 紀錄頁「現金收支」：月份分組列表與合計；收支會直接加減帳本現金 */
@Composable
fun CashFlowPane(onEdit: (CashFlow) -> Unit) {
    var filter by remember { mutableIntStateOf(0) }
    val all = AppState.cashFlows.toList()
    val list = all.filter { filter == 0 || (filter == 1 && it.amount > 0) || (filter == 2 && it.amount < 0) }
    val month = LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("yyyy/MM"))
    val mIn = all.filter { it.date.startsWith(month) && it.amount > 0 }.sumOf { it.amount }
    val mOut = all.filter { it.date.startsWith(month) && it.amount < 0 }.sumOf { -it.amount }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(padding = 14.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Label("本月收入"); NumText(mask("+" + Fmt.num(mIn)), 16.sp, C.Green, FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Label("本月支出"); NumText(mask("−" + Fmt.num(mOut)), 16.sp, C.Red, FontWeight.Bold) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Label("本月淨額"); val net = mIn - mOut
                        NumText(mask((if (net >= 0) "+" else "−") + Fmt.num(abs(net))), 16.sp, if (net >= 0) C.Green else C.Red, FontWeight.Bold)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(C.Border))
                KeyValue("累計收支淨額（全部）", mask((if (AppState.cashFlowNet >= 0) "+" else "−") + "NT$" + Fmt.num(abs(AppState.cashFlowNet))))
                KeyValue("目前現金（含股票交易）", mask("NT$" + Fmt.num(AppState.cashTwd)), if (AppState.cashTwd < 0) C.Red else C.Text)
                Label("收支會直接加減到帳本現金，總資產與資產配置同步更新；股票的買賣與股息仍由交易紀錄計算。")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "收入", "支出").forEachIndexed { i, n ->
                    val on = i == filter
                    Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Selected else C.Card)
                            .clickable { filter = i }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        if (list.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("還沒有現金收支紀錄")
                Text("＋ 新增一筆", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { onEdit(CashFlow(0L, "", 0.0, "")) }.padding(10.dp))
            }
        }
        list.groupBy { it.date.take(7) }.forEach { (m, fs) ->
            item {
                val i = fs.filter { it.amount > 0 }.sumOf { it.amount }
                val o = fs.filter { it.amount < 0 }.sumOf { -it.amount }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Label(listOfNotNull(if (i > 0) "收 " + mask(Fmt.num(i)) else null, if (o > 0) "支 " + mask(Fmt.num(o)) else null).joinToString(" · "))
                }
            }
            items(fs, key = { "cf" + it.id }) { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { onEdit(f) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (f.amount >= 0) "收" else "支", color = C.OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (f.amount >= 0) C.Green else C.Red).padding(horizontal = 7.dp, vertical = 3.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(f.category.ifBlank { if (f.amount >= 0) "收入" else "支出" }, color = C.Text, fontSize = 14.sp)
                        Label(f.date.drop(5) + (if (f.note.isNotBlank()) " · " + f.note else ""))
                    }
                    NumText(mask((if (f.amount >= 0) "+" else "−") + Fmt.num(abs(f.amount))), 15.sp, if (f.amount >= 0) C.Green else C.Red, FontWeight.Bold)
                }
            }
        }
        item { Label("點一筆可以修改或刪除。", Modifier.padding(top = 4.dp)) }
    }
}

/** 新增／編輯現金收支 */
@Composable
fun CashFlowDialog(f0: CashFlow, onClose: () -> Unit) {
    val isNew = f0.id == 0L
    var income by remember { mutableStateOf(f0.amount >= 0) }
    var amount by remember { mutableStateOf(if (isNew) "" else Fmt.num(abs(f0.amount), 0).replace(",", "")) }
    var date by remember { mutableStateOf(f0.date.ifBlank { today() }) }
    var cat by remember { mutableStateOf(f0.category.ifBlank { "入金" }) }
    var note by remember { mutableStateOf(f0.note) }
    var confirmDel by remember { mutableStateOf(false) }
    val value = amount.replace(",", "").trim().toDoubleOrNull()
    val ok = value != null && value > 0 && runCatching { LocalDate.parse(date.trim().replace("-", "/"), DateTimeFormatter.ofPattern("yyyy/MM/dd")) }.isSuccess
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Card,
        title = { Text(if (isNew) "新增現金收支" else "編輯現金收支", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf("收入", "支出"), if (income) 0 else 1, {
                    income = it == 0
                    cat = if (income) IN_CATS.first() else OUT_CATS.first()
                }, background = C.Bg)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("金額（台幣）") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                DateField("日期", date, { date = it }, Modifier.fillMaxWidth())
                Label("類別")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (income) IN_CATS else OUT_CATS).forEach { c ->
                        val on = c == cat
                        Text(c, color = if (on) C.OnPrimary else C.Text2, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg)
                                .clickable { cat = c }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, singleLine = true, label = { Text("備註（選填）") },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                if (!isNew) Text(if (confirmDel) "再按一次確定刪除" else "刪除這筆", color = C.Red, fontSize = 13.sp,
                    modifier = Modifier.clickable { if (confirmDel) { AppState.deleteCashFlow(f0.id); onClose() } else confirmDel = true }.padding(vertical = 6.dp))
            }
        },
        confirmButton = {
            Text("儲存", color = if (ok) C.Primary else C.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = ok) {
                    val v = value ?: return@clickable
                    AppState.saveCashFlow(CashFlow(f0.id, date.trim().replace("-", "/"), if (income) v else -v, cat, note.trim()))
                    onClose()
                }.padding(12.dp))
        },
        dismissButton = { Text("取消", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose).padding(12.dp)) },
    )
}
