package com.ppnkdeapp.mycontacts

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ContactEditDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onSave: (Contact) -> Unit,
    onDelete: (Contact) -> Unit // Новый параметр для удаления
) {
    var editedContact by remember { mutableStateOf(contact) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    @RequiresPermission(Manifest.permission.VIBRATE)
    fun performVibration() {
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        30, // длительность в миллисекундах
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Заголовок с кнопкой удаления
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Редактирование контакта",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary
                    )

                    // Кнопка корзины
                    IconButton(
                        onClick = {
                            showDeleteConfirmation = true
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text(
                            text = "🗑️", // Иконка корзины
                            fontSize = 18.sp
                        )
                    }
                }

                // Поле для имени
                Text(
                    text = "Имя:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = editedContact.Name!!,
                    onValueChange = { newName ->
                        editedContact = editedContact.copy(Name = newName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true
                )

                // Поле для email
                Text(
                    text = "Email:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = editedContact.email!!,
                    onValueChange = { newEmail ->
                        editedContact = editedContact.copy(email = newEmail)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true
                )

                // Поле для personal_id
                // Поле для personal_id
                Text(
                    text = "Personal ID:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = editedContact.personal_id ?: "", // Если null - пустая строка
                    onValueChange = { newId ->
                        // Если строка пустая - устанавливаем null, иначе сохраняем как есть
                        editedContact = editedContact.copy(personal_id = if (newId.isBlank()) null else newId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true,
                    placeholder = {
                        Text("Введите ID")
                    }
                )

                // Поле для group_id
//                Text(
//                    text = "Group ID:",
//                    fontSize = 14.sp,
//                    fontWeight = FontWeight.Medium,
//                    color = Color.Gray,
//                    modifier = Modifier.padding(bottom = 4.dp)
//                )
//                OutlinedTextField(
//                    value = editedContact.group_id.toString(),
//                    onValueChange = { newGroupId ->
//                        val groupId = newGroupId.toIntOrNull() ?: editedContact.group_id
//                        editedContact = editedContact.copy(group_id = groupId)
//                    },
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(bottom = 16.dp),
//                    singleLine = true
//                )

                // Чекбокс для root_contact
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
//                    Checkbox(
//                        checked = editedContact.root_contact == true,
//                        onCheckedChange = { isChecked ->
//                            editedContact = editedContact.copy(root_contact = if (isChecked) true else null)
//                        }
//                    )
//                    Text(
//                        text = "Root контакт",
//                        fontSize = 14.sp,
//                        fontWeight = FontWeight.Medium,
//                        modifier = Modifier.padding(start = 8.dp)
//                    )
                }

                // Кнопки Сохранить и Отменить
                // Кнопки Сохранить и Отменить
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            performVibration()
                            onSave(editedContact)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Сохранить")
                    }

                    Spacer(modifier = Modifier.width(24.dp)) // Явный разделитель

                    TextButton(
                        onClick = {
                            performVibration()
                            onDismiss()
                        }
                    ) {
                        Text("Отменить")
                    }
                }
//
            }
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    text = "Удаление контакта",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Вы уверены, что хотите удалить контакт \"${editedContact.Name}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(editedContact) // Передаем текущий editedContact
                        onDismiss()
                    }
                ) {
                    Text("Удалить", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}